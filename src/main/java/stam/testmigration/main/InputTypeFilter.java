package stam.testmigration.main;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import stam.testmigration.setup.SetupTargetApp;

import java.io.File;
import java.util.*;

public class InputTypeFilter {

    static Map<String, String> concreteClasses = new HashMap<>();

    NodeList<VariableDeclarator> getFilteredInputs(NodeList<Parameter> targetParams, NodeList<VariableDeclarator> potentialVars){
        NodeList<VariableDeclarator> filteredInputs = new NodeList<>();
        List<String> targetParamTypes = getTargetParamTypes(targetParams);
        if(!targetParamTypes.isEmpty()){
            potentialVars.forEach(var -> {
                for(String varType : getType(var)){
                    if(toTargetTypes(varType, targetParamTypes)){
                        filteredInputs.add(var);
                    }
                }
            });
        }
        if(targetParamTypes.contains("Context"))
            filteredInputs.add(new VariableDeclarator().setType("Context").setName("context"));
        return filteredInputs;
    }

    //check if the potential variable can be converted to one of the target param types
    boolean toTargetTypes(String type, List<String> targetParamTypes){
        boolean canBeConverted = false;
        String path = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getTargetDir()), type+".java");
        //TODO: keep improving the potential type conversion list
        if(targetParamTypes.contains(type)){
            canBeConverted = true;
        }else if(targetParamTypes.contains("T")){
            //generic type
            canBeConverted = true;
        }else if(targetParamTypes.contains("Object") && !type.contains("[]")){
            ArrayList<String> primitiveTypes = new ArrayList<>(Arrays.asList("int", "long", "double", "float", "short", "char", "byte"));
            if(!primitiveTypes.contains(type)){
                canBeConverted = true;
            }
        }else if(targetParamTypes.contains("Writer")){
            ArrayList<String> writerTypes = new ArrayList<>(Arrays.asList("BufferedWriter", "CharArrayWriter", "FilterWriter", "OutputStreamWriter", "PipedWriter", "PrintWriter", "StringWriter"));
            if(writerTypes.contains(type)){
                canBeConverted = true;
            }
        }else if(type.contains("[]") && targetParamTypes.contains("T[]")){
            ArrayList<String> primitiveArrays = new ArrayList<>(Arrays.asList("int[]", "long[]", "double[]", "float[]", "short[]", "char[]", "byte[]", "boolean[]"));
            if(!primitiveArrays.contains(type)){
                canBeConverted = true;
            }
        }else if(type.equals("File")){
            if(targetParamTypes.contains("FileInputStream") || targetParamTypes.contains("FileOutputStream") ||
                    targetParamTypes.contains("String") || targetParamTypes.contains("BufferedInputStream") ||
                    targetParamTypes.contains("BufferedOutputStream") || targetParamTypes.contains("ReadableByteChannel")
                    || targetParamTypes.contains("WritableByteChannel"))
                canBeConverted = true;
        }else if(type.equals("int")){
            if(targetParamTypes.contains("Integer") || targetParamTypes.contains("long") || targetParamTypes.contains("double")
            || targetParamTypes.contains("float"))
                canBeConverted = true;
        }else if(type.equals("int[]")){
            if(targetParamTypes.contains("int") || targetParamTypes.contains("Integer"))
                canBeConverted = true;
        }else if(type.equals("byte[]")){
            if(targetParamTypes.contains("byte"))
                canBeConverted = true;
        }else if((type.equals("byte") || type.equals("short")) && (targetParamTypes.contains("int"))){
            canBeConverted = true;
        }else if(type.contains("<") && type.contains(">")){
            canBeConverted = checkIterable(type, targetParamTypes);
            //replace type argument with T
            String typeArgument = type.substring(type.lastIndexOf("<")+1, type.indexOf(">"));
            type = type.replace(typeArgument, "T");
            if(targetParamTypes.contains(type)){
                canBeConverted = true;
            }
            type = type.substring(0, type.indexOf("<"));
            for(String targetType: targetParamTypes){
                if(targetType.contains("<") && type.equals(targetType.substring(0, targetType.indexOf("<")))){
                    canBeConverted = true;
                    break;
                }
            }
        }else if(type.equals("CharSequence") && targetParamTypes.contains("String")){
            canBeConverted = true;
        }else if(type.equals("Object")){
            canBeConverted = true;
        }else if(type.equals("Number") && targetParamTypes.contains("Object")){
            canBeConverted = true;
        }else if(type.equals("BigInteger") && targetParamTypes.contains("Number")){
            canBeConverted = true;
        }else if(type.equals("BigDecimal") && targetParamTypes.contains("Number")){
            canBeConverted = true;
        }else if(path != null){
            canBeConverted = checkAbstractClass(type, targetParamTypes);
        }
        return canBeConverted;
    }

    //get extended types if available
    private ArrayList<String> getType(VariableDeclarator var){
        ArrayList<String> extendedTypes = new ArrayList<>();
        extendedTypes.add(var.getTypeAsString());

        if(var.getType().isClassOrInterfaceType()){
            String path = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getTargetDir()), var.getTypeAsString()+".java");
            if(path != null){
                CompilationUnit unit = SetupTargetApp.getCompilationUnit(new File(path));
                unit.findAll(ClassOrInterfaceDeclaration.class).forEach(node -> {
                    if(node.getNameAsString().equals(var.getTypeAsString())){
                        node.getExtendedTypes().forEach(type -> {
                            extendedTypes.add(type.getNameAsString());
                        });
                    }
                });
            }
        }
        return extendedTypes;
    }

    //get param types in target method or object
    private List<String> getTargetParamTypes(NodeList<Parameter> targetParams){
        List<String> types = new ArrayList<>();
        targetParams.forEach(param -> types.add(param.getTypeAsString()));
        return types;
    }

    private boolean checkAbstractClass(String concreteType, List<String> tagetParams){
        String path = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getTargetDir()), concreteType+".java");
        final boolean[] convertible = {false};
        if(path != null){
            CompilationUnit cUnit = SetupTargetApp.getCompilationUnit(new File(path));
            cUnit.accept(new VoidVisitorAdapter<Object>() {
                @Override
                public void visit(ClassOrInterfaceDeclaration node, Object arg){
                    super.visit(node, arg);
                    if(node.getNameAsString().equals(concreteType)){
                        for(ClassOrInterfaceType type: node.getImplementedTypes()){
                            if(tagetParams.contains(type.getNameAsString())){
                                concreteClasses.put(concreteType, type.getNameAsString());
                                convertible[0] = true;
                            }
                        }
                    }
                }
            }, null);
        }
        return convertible[0];
    }

    private boolean checkIterable(String sourceType, List<String> targetParamTypes){
        String type = sourceType.substring(0, sourceType.indexOf("<"));

        ArrayList<String> iterablesInTarget = new ArrayList<>();
        for(String param: targetParamTypes){
            if(param.contains("<")){
                iterablesInTarget.add(param.substring(0, param.indexOf("<")));
            }
        }

        ArrayList<String> iterables = new ArrayList<>(Arrays.asList("Iterable", "List", "ArrayList", "Collection"));
        if(iterables.contains(type)){
            //at least one target type is iterable
            for(String targetParam: iterablesInTarget){
                if(iterables.contains(targetParam)){
                    return true;
                }
            }
        }
        return false;
    }
}
