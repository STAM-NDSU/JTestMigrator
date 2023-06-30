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
                    if(compatibleTypeExists(varType, targetParamTypes)){
                        filteredInputs.add(var);
                    }
                }
            });
        }
        return filteredInputs;
    }

    //check if the potential variable can be converted to one of the target param types
    boolean compatibleTypeExists(String type, List<String> targetParamTypes){
        ArrayList<String> primitiveTypes = new ArrayList<>(Arrays.asList("int", "long", "double", "float", "short", "char", "byte", "boolean"));
        ArrayList<String> primitiveArrays = new ArrayList<>(Arrays.asList("int[]", "long[]", "double[]", "float[]", "short[]", "char[]", "byte[]", "boolean[]"));
        ArrayList<String> writerTypes = new ArrayList<>(Arrays.asList("BufferedWriter", "CharArrayWriter", "FilterWriter", "OutputStreamWriter", "PipedWriter", "PrintWriter", "StringWriter"));

        if(targetParamTypes.contains(type) || containsGenericType(targetParamTypes)){
            return true;
        }else if(targetParamTypes.contains("Object") && !type.contains("[]") && !primitiveTypes.contains(type)){
            return true;
        }else if(targetParamTypes.contains("Writer") && writerTypes.contains(type)){
            return true;
        }else if(type.contains("[]") && targetParamTypes.contains("T[]") && !primitiveArrays.contains(type)){
            return true;
        }else if(type.equals("File") && (targetParamTypes.contains("FileInputStream") || targetParamTypes.contains("FileOutputStream")
                || targetParamTypes.contains("String") || targetParamTypes.contains("BufferedInputStream") || targetParamTypes.contains("BufferedOutputStream")
                || targetParamTypes.contains("ReadableByteChannel") || targetParamTypes.contains("WritableByteChannel"))){
            return true;
        }else if(type.equals("int") && (targetParamTypes.contains("Integer") || targetParamTypes.contains("long")
                || targetParamTypes.contains("double") || targetParamTypes.contains("float"))){
            return true;
        }else if(type.equals("int[]") && (targetParamTypes.contains("int") || targetParamTypes.contains("Integer"))){
            return true;
        }else if(type.equals("byte[]") && targetParamTypes.contains("byte")){
            return true;
        }else if((type.equals("byte") || type.equals("short")) && (targetParamTypes.contains("int"))){
            return true;
        }else if(type.contains("<") && type.contains(">")){
            if(checkIterable(type, targetParamTypes)) return true;
            //replace type argument with T
            String typeArgument = type.substring(type.lastIndexOf("<")+1, type.indexOf(">"));
            type = type.replace(typeArgument, "T");
            if(targetParamTypes.contains(type)) return true;

            type = type.substring(0, type.indexOf("<"));
            for(String targetType: targetParamTypes){
                if(targetType.contains("<") && type.equals(targetType.substring(0, targetType.indexOf("<")))) return true;
            }
        }else if(type.equals("CharSequence") && targetParamTypes.contains("String")){
            return true;
        }else if(type.equals("Object")){
            if(targetParamTypes.size()>1){
                return true;
            }else if(targetParamTypes.size()==1 && !primitiveTypes.contains(targetParamTypes.get(0))){
                return true;
            };
        }else if(type.equals("Number") && targetParamTypes.contains("Object")){
            return true;
        }else if(type.equals("BigInteger") && targetParamTypes.contains("Number")){
            return true;
        }else if(type.equals("BigDecimal") && targetParamTypes.contains("Number")){
            return true;
        }else{
            String path = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getTargetDir()), type+".java");
            if(path != null) return checkAbstractClass(path, type, targetParamTypes);
        }
        return false;
    }

    private boolean containsGenericType(List<String> types){
        for(String type: types){
            if(type.length()==1 && Character.isUpperCase(type.charAt(0))) return true;
        }
        return false;
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

    private boolean checkAbstractClass(String path, String concreteType, List<String> tagetParams){
        final boolean[] convertible = {false};
        SetupTargetApp.getCompilationUnit(new File(path)).accept(new VoidVisitorAdapter<Object>() {
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
                if(iterables.contains(targetParam)) return true;
            }
        }
        return false;
    }
}
