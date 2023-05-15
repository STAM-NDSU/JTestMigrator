package stam.testmigration.main;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import stam.testmigration.setup.SetupTargetApp;

import java.io.File;
import java.util.*;

public class ConstructorMapper {
    static Map<String, ConstructorDeclaration> sourceConstructors = new HashMap<>();
    static Map<String, ConstructorDeclaration> targetConstructors = new HashMap<>();
    static Map<String, ConstructorDeclaration> replacedSourceConstructors = new HashMap<>();

    void mapConstructors(CompilationUnit modifiedTestCU){
        for(ObjectCreationExpr expr : getObjectsInTest(modifiedTestCU)){
            sourceConstructors.put(expr.toString(), getSourceConstructor(expr));
        }
    }

    void findTargetConstructor(ObjectCreationExpr expr, String replacedClass){
        if(sourceConstructors.containsKey(expr.toString())){
            String replacedClassPath = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getTargetDir()), replacedClass+".java");
            if(replacedClassPath != null){
                ConstructorDeclaration targetConstructor = getTargetConstructor(expr, SetupTargetApp.getCompilationUnit(new File(replacedClassPath)), replacedClass);
                String replacedExpr = expr.clone().setType(replacedClass).toString();
                targetConstructors.put(replacedExpr, targetConstructor);
                replacedSourceConstructors.put(replacedExpr, sourceConstructors.get(expr.toString()));
            }
        }
    }

    private ConstructorDeclaration getTargetConstructor(ObjectCreationExpr expr, CompilationUnit targetCU, String className){
        List<ConstructorDeclaration> constructors = new ArrayList<>(targetCU.findAll(ConstructorDeclaration.class));
        if(constructors.isEmpty()){
            return new ConstructorDeclaration().setName(className);
        }else if(constructors.size() == 1){
            return constructors.get(0);
        }else{
            return selectConstructor(sourceConstructors.get(expr.toString()), constructors);
        }
    }

    private ConstructorDeclaration selectConstructor(ConstructorDeclaration sourceConstructor, List<ConstructorDeclaration> targetConstructors){
        //select a target constructor by matching param size and types
        for(ConstructorDeclaration targetConstructor: targetConstructors){
            if(matchParamTypes(sourceConstructor, targetConstructor)){
                return targetConstructor;
            }
        }
        //select an empty constructor
        for(ConstructorDeclaration targetConstructor: targetConstructors){
            if(targetConstructor.getParameters().isEmpty()){
                return targetConstructor;
            }
        }
        //select a target constructor by matching param size
        for(ConstructorDeclaration targetConstructor: targetConstructors){
            if(matchParamSize(sourceConstructor, targetConstructor)){
                return targetConstructor;
            }
        }
        //select any one constructor
        return targetConstructors.get(0);
    }

    private boolean matchParamTypes(ConstructorDeclaration source, ConstructorDeclaration target){
        if(source.getParameters().size() == target.getParameters().size()){
            ArrayList<String> sourceParams = getParamTypes(source);
            ArrayList<String> targetParams = getParamTypes(target);
            for(int i=0; i<sourceParams.size(); i++){
                if(!new InputTypeFilter().toTargetTypes(sourceParams.get(0), new ArrayList<>(List.of(targetParams.get(0))))){
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private boolean matchParamSize(ConstructorDeclaration source, ConstructorDeclaration target){
        return source.getParameters().size() == target.getParameters().size();
    }

    private ConstructorDeclaration getSourceConstructor(ObjectCreationExpr expr){
        final ConstructorDeclaration[] cd = {null};
        new MethodMatcher().getTestCompilationFromSourceApp().accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(ObjectCreationExpr creationExpr, Object arg){
                super.visit(creationExpr, arg);
                if(creationExpr.equals(expr)){
                    cd[0] = getConstructorDecl(creationExpr.resolve());
                }
            }
        }, null);
        //for default constructor
        if(expr.getArguments().isEmpty() && cd[0] == null){
            cd[0] = new ConstructorDeclaration().setName(expr.getTypeAsString());
        }
        return cd[0];
    }

    private ConstructorDeclaration getConstructorDecl(ResolvedConstructorDeclaration rcd){
        final ConstructorDeclaration[] cd = {null};
        ArrayList<String> paramTypes = getConstructorParamTypes(rcd);
        String className = rcd.getClassName();
        String path = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getSourceDir()), className+".java");
        if(path != null){
            CompilationUnit unit = SetupTargetApp.getCompilationUnit(new File(path));
            unit.accept(new VoidVisitorAdapter<Object>() {
                @Override
                public void visit(ConstructorDeclaration declaration, Object arg){
                    super.visit(declaration, arg);
                    if(declaration.getParameters().size() == paramTypes.size()){
                        ArrayList<String> sourceParamTypes = getParamTypes(declaration);
                        if(paramTypes.equals(sourceParamTypes)){
                            cd[0] = declaration;
                        }
                    }
                }
            }, null);
        }
        return cd[0];
    }

    ArrayList<String> getParamTypes(ConstructorDeclaration declaration){
        ArrayList<String> sourceParamTypes = new ArrayList<>();
        declaration.getParameters().forEach(parameter -> {
            String type = parameter.getTypeAsString();
            if(type.contains("<")){
                type = type.substring(0, type.indexOf("<"));
            }
            sourceParamTypes.add(type);
        });
        return sourceParamTypes;
    }

    private ArrayList<String> getConstructorParamTypes(ResolvedConstructorDeclaration rcd){
        int paramSize = rcd.getNumberOfParams();
        ArrayList<String> paramTypes= new ArrayList<>();
        for(int i=0; i<paramSize; i++){
            String paramType = rcd.getParam(i).getType().describe();

            if(paramType.contains("<")){
                paramType = paramType.substring(0, paramType.indexOf("<"));
            }
            if(paramType.contains(".")){
                paramType = paramType.substring(paramType.lastIndexOf(".")+1);
            }

            paramTypes.add(paramType);
        }
        return paramTypes;
    }

    private HashSet<ObjectCreationExpr> getObjectsInTest(CompilationUnit modifiedTestCU){
        HashSet<ObjectCreationExpr> objectsInTest = new HashSet<>();
        modifiedTestCU.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(ObjectCreationExpr expr, Object arg){
                super.visit(expr, arg);
                if(new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getSourceDir()), expr.getTypeAsString()+".java") != null){
                    objectsInTest.add(expr);
                }
            }
        }, null);
        return objectsInTest;
    }
}
