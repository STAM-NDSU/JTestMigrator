package stam.testmigration.main;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import stam.testmigration.setup.SetupTargetApp;

import java.io.File;
import java.util.*;

public class InputTypeConverter {

    SetupTargetApp setupTargetApp = new SetupTargetApp();

    //get a combination of args for method or object
    Map<Integer, List<String>> getArguments(NodeList<Parameter> params, NodeList<VariableDeclarator> inputs,
                                            CompilationUnit cu, String methodOrConstructorName){
        Map<Integer, List<String>> args = new HashMap<>();
        ArrayList<Parameter> inputList = new ArrayList<>();
        for(int i=0; i<params.size(); i++){
            String type = params.get(i).getTypeAsString();
            String name = params.get(i).getNameAsString();
            List<String> argValues = new ArrayList<>();
            if(inputs.isEmpty()){
                argValues.add(name);
                if(!inputList.contains(params.get(i))){
                    inputList.add(params.get(i));
                }
            }
            for(VariableDeclarator variableDeclarator: inputs){
                String varType = variableDeclarator.getTypeAsString();
                String value = variableDeclarator.getNameAsString();

                if(type.equals("Object") && !varType.contains("[]")){
                    argValues.add(value);
                }else if(type.equals("T")){
                    //generics
                    argValues.add(value);
                }else if(type.contains("<T>")){
                    //generic type argument
                    if(varType.contains("<") && varType.contains(">")){
                        String typeArgument = varType.substring(varType.lastIndexOf("<")+1, varType.indexOf(">"));
                        String modifiedType = varType.replace(typeArgument, "T");
                        if(type.equals(modifiedType)){
                            argValues.add(value);
                        }
                    }
                }else if(type.equals("T[]") && varType.contains("[]")){
                    argValues.add(value);
                }else if(type.contains("<") && varType.contains("<")){
                    String sourceType = type.substring(0, type.indexOf("<"));
                    String targetType = varType.substring(0, varType.indexOf("<"));
                    ArrayList<String> iterables = new ArrayList<>(Arrays.asList("Iterable", "List", "ArrayList", "Collection"));
                    if(sourceType.equals(targetType)){
                        if(isNotReachable(variableDeclarator, methodOrConstructorName, cu)){
                            argValues.add(name);
                            if(!inputList.contains(params.get(i))){
                                inputList.add(params.get(i));
                            }
                        }
                        argValues.add(value);
                    }else if(iterables.contains(sourceType) && iterables.contains(targetType)){
                        argValues.add(value);
                    }
                }else if(varType.equals(type)){
                    if(varType.equals("File")){
                        argValues.add("new File("+value+".getParent())");
                        argValues.add(value);
                    }else {
                        argValues.add(value);
                        name = value;
                    }
                }else if(getFileTypes().contains(type) && getFileTypes().contains(varType)){
                    if(varType.equals("File")){
                        switch (type) {
                            case "FileInputStream":
                                argValues.add("new FileInputStream(" + value + ")");
                                break;
                            case "FileOutputStream":
                                argValues.add("new FileOutputStream(" + value + ")");
                                break;
                            case "BufferedInputStream":
                                argValues.add("new BufferedInputStream(new FileInputStream(" + value + "))");
                                break;
                            case "BufferedOutputStream":
                                argValues.add("new BufferedOutputStream(new FileOutputStream(" + value + "))");
                                break;
                            case "ReadableByteChannel":
                                argValues.add("new FileInputStream(" + value + ").getChannel()");
                                break;
                            case "WritableByteChannel":
                                argValues.add("Channels.newChannel(new FileOutputStream(" + value + "))");
                                break;
                            case "String":
                                argValues.add(value+".getParent()");
                                argValues.add(value+".getAbsolutePath()");
                                argValues.add(value+".getName()");
                                //check if the project is an Android app
                                String path = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getTargetDir()),
                                        "AndroidManifest.xml");
                                if(path != null){
                                    addImport("android.os.Environment", cu);
                                    argValues.add("Environment.getExternalStorageDirectory().getAbsolutePath()");
                                }
                                break;
                        }
                    }
                }else if(varType.equals("int") && (type.equals("long") || type.equals("double") || type.equals("float"))){
                    argValues.add(value);
                }else if((varType.equals("byte") || varType.equals("short")) && type.equals("int")){
                    argValues.add(value);
                }else if(InputTypeFilter.concreteClasses.containsKey(varType) && InputTypeFilter.concreteClasses.get(varType).equals(type)){
                    argValues.add(value);
                }else if(type.equals("Number") && varType.equals("BigInteger")){
                    argValues.add(value);
                }else if(type.equals("Number") && varType.equals("BigDecimal")){
                    argValues.add(value);
                }else if(type.equals("Writer")){
                    ArrayList<String> writerTypes = new ArrayList<>(Arrays.asList("BufferedWriter", "CharArrayWriter", "FilterWriter", "OutputStreamWriter", "PipedWriter", "PrintWriter", "StringWriter"));
                    if(writerTypes.contains(varType)){
                        argValues.add(value);
                    }
                }else{
                    if(!argValues.contains(name)) {
                        argValues.add(name);
                        if(!inputList.contains(params.get(i))){
                            inputList.add(params.get(i));
                        }
                    }
                }
            }
            //remove duplicate args
            args.put(i, new ArrayList<>(new HashSet<>(argValues)));
        }
        new InputGenerator(cu, methodOrConstructorName, inputList).generateInput();
        return args;
    }

    //Convertible File types
    private List<String> getFileTypes(){
        return Arrays.asList("File", "FileInputStream", "FileOutputStream", "BufferedInputStream",
                "BufferedOutputStream", "ReadableByteChannel", "WritableByteChannel", "String");
    }

    //add android.os.Environment import
    private void addImport(String importName, CompilationUnit testFileCU){
        NodeList<ImportDeclaration> imports = testFileCU.getImports();
        boolean importExists = false;
        for(ImportDeclaration id : imports){
            if(id.getNameAsString().equals(importName)){
               importExists = true;
            }
        }
        if(!importExists){
            testFileCU.addImport(importName);
        }
    }

    private boolean isNotReachable(VariableDeclarator vd, String methodOrConstructor, CompilationUnit cu){
        final int[] methodCallNum = {0};
        final boolean[] isFieldVar = {false};
        if(MethodMatcher.similarMethods.containsValue(methodOrConstructor)){
            cu.accept(new VoidVisitorAdapter<Object>() {
                @Override
                public void visit(MethodCallExpr callExpr, Object arg){
                    super.visit(callExpr, arg);
                    if(callExpr.getNameAsString().equals(methodOrConstructor)){
                        methodCallNum[0]++;
                    }
                }

                @Override
                public void visit(FieldDeclaration node, Object arg){
                    super.visit(node, arg);
                    for(VariableDeclarator declarator: node.getVariables()){
                        if(declarator.getNameAsString().equals(vd.getNameAsString())){
                            isFieldVar[0] = true;
                            break;
                        }
                    }
                }
            }, null);
        }
        if(!isFieldVar[0] && methodCallNum[0]>1){
            return true;
        }
        return false;
    }

}
