package stam.testmigration.main;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import stam.testmigration.search.CodeSearchResults;
import stam.testmigration.setup.SetupTargetApp;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class MethodCallResolver {

    static Map<MethodCallExpr, MethodDeclaration> resolvedMethodCalls = new HashMap<>();
    static Map<MethodCallExpr, ArrayList<String>> sourceParamTypes = new HashMap<>();
    static ArrayList<MethodCallExpr> javaAPIs = new ArrayList<>();

    void resolveCalls(){
        ArrayList<MethodCallExpr> methodCalls = getMethodsCalledInTest();

        Objects.requireNonNull(ClassObjectModifier.getTestCompilationFromSourceApp()).accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodCallExpr expr, Object arg){
                super.visit(expr, arg);
                MethodCallExpr clonedExpr = expr.clone().removeScope();
                if(methodCalls.contains(clonedExpr) && !resolvedMethodCalls.containsKey(expr)){
                    MethodDeclaration methodDeclaration = null;
                    try {
                        methodDeclaration = getMethodDecl(expr.resolve());
                    }catch(RuntimeException ignore){}
                    if(methodDeclaration != null){
                        resolvedMethodCalls.put(expr, methodDeclaration);
                    }else if(expr.getNameAsString().equals(new CodeSearchResults().getSourceTestMethod())){
                        MethodDeclaration selectedMethod = selectMethodDecl(expr);
                        if(selectedMethod != null){
                            resolvedMethodCalls.put(expr, selectedMethod);
                        }
                    }
                }
                //check Java apis
                try{
                    if(expr.resolve().getQualifiedName().startsWith("java") || expr.getNameAsString().equals("toString")){
                        javaAPIs.add(expr);
                    }
                }catch(Exception ignore){}

            }
        }, null);

        for(Map.Entry<MethodCallExpr, MethodDeclaration> entry: resolvedMethodCalls.entrySet()){
            ArrayList<String> paramTypes = new ArrayList<>();
            entry.getValue().getParameters().forEach(param -> paramTypes.add(param.getTypeAsString()));
            //adjust param types for varArg
            int paramDiffSize = entry.getKey().getArguments().size()-paramTypes.size();
            if(paramDiffSize > 0){
                String type = paramTypes.get(paramTypes.size()-1);
                for(int i=0; i<paramDiffSize; i++){
                    paramTypes.add(type);
                }
            }
            sourceParamTypes.put(entry.getKey().removeScope(), paramTypes);
        }
    }

    private MethodDeclaration getMethodDecl(ResolvedMethodDeclaration rmd){
        final MethodDeclaration[] methodDeclaration = {null};
        ArrayList<String> paramTypes = getMethodParamTypes(rmd);
        String className = rmd.getClassName();
        String path = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getSourceDir()), className+".java");
        if(path != null){
            CompilationUnit sourceCU = SetupTargetApp.getCompilationUnit(new File(path));
            sourceCU.accept(new VoidVisitorAdapter<Object>() {
                @Override
                public void visit(MethodDeclaration declaration, Object arg){
                    super.visit(declaration, arg);
                    if(rmd.getName().equals(declaration.getNameAsString()) && declaration.getParameters().size() == paramTypes.size()){
                        ArrayList<String> sourceParamTypes = new ArrayList<>();
                        declaration.getParameters().forEach(parameter -> {
                            sourceParamTypes.add(parameter.getTypeAsString());
                        });
                        if(paramTypes.equals(sourceParamTypes)){
                            methodDeclaration[0] = declaration;
                        }
                    }
                }
            }, null);
        }
        return methodDeclaration[0];
    }

    private MethodDeclaration selectMethodDecl(MethodCallExpr callExpr){
        final MethodDeclaration[] methodDeclaration = {null};
        String callName = callExpr.getNameAsString();
        String path = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getSourceDir()), new CodeSearchResults().getSourceFileName());
        if(path != null){
            CompilationUnit sourceCU = SetupTargetApp.getCompilationUnit(new File(path));
            sourceCU.accept(new VoidVisitorAdapter<Object>() {
                @Override
                public void visit(MethodDeclaration declaration, Object arg){
                    super.visit(declaration, arg);
                    if(declaration.getNameAsString().equals(callName) && callExpr.getArguments().size() == declaration.getParameters().size()){
                        methodDeclaration[0] = declaration;
                    }else if (declaration.getNameAsString().equals(callName)){
                        final boolean[] isVarArg = {false};
                        declaration.getParameters().forEach(parameter -> {
                            if(parameter.isVarArgs()){
                                isVarArg[0] = true;
                            }
                        });
                        if(isVarArg[0]){
                            methodDeclaration[0] = declaration;
                        }
                    }
                }
            }, null);
        }
        return methodDeclaration[0];
    }

    private ArrayList<String> getMethodParamTypes(ResolvedMethodDeclaration rmd){
        int paramSize = rmd.getNumberOfParams();
        ArrayList<String> paramTypes= new ArrayList<>();
        for(int i=0; i<paramSize; i++){
            String paramType = rmd.getParam(i).getType().describe();
            if(paramType.contains(".")){
                paramTypes.add(paramType.substring(paramType.lastIndexOf(".")+1));
            }else{
                paramTypes.add(paramType);
            }

        }
        return paramTypes;
    }

    private ArrayList<MethodCallExpr> getMethodsCalledInTest(){
        ArrayList<MethodCallExpr> methodCalls = new ArrayList<>();
        for(ArrayList<MethodCallExpr> exprs: TestModifier.replacedMethods.values()){
            for(MethodCallExpr callExpr: exprs){
                methodCalls.add(callExpr.removeScope());
            }
        }
        return methodCalls;
    }
}
