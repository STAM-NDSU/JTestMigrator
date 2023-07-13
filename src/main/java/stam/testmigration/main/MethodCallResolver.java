package stam.testmigration.main;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import stam.testmigration.search.CodeSearchResults;
import stam.testmigration.setup.SetupTargetApp;

import java.io.File;
import java.util.*;

public class MethodCallResolver {
    static Map<String, MethodDeclaration> resolvedCalls = new HashMap<>();
    static Map<MethodCallExpr, ArrayList<String>> sourceParamTypes = new HashMap<>();
    static ArrayList<MethodCallExpr> javaAPIs = new ArrayList<>();

    void resolveCalls(CompilationUnit cu){
        CodeSearchResults csr = new CodeSearchResults();
        Set<String> methodCalls = getMethodsCalledInTest(cu, csr);
        Map<MethodCallExpr, MethodDeclaration> resolvedMethodCalls = new HashMap<>();
        Objects.requireNonNull(ClassObjectModifier.getTestCompilationFromSourceApp()).accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodCallExpr expr, Object arg){
                super.visit(expr, arg);
                if(methodCalls.contains(expr.toString()) && !resolvedMethodCalls.containsKey(expr)){
                    MethodDeclaration methodDeclaration = null;
                    try {
                        methodDeclaration = getMethodDecl(expr.resolve());
                    }catch(RuntimeException exception){
                        javaAPIs.add(expr);
                    }

                    if(methodDeclaration != null){
                        resolvedMethodCalls.put(expr, methodDeclaration);
                    }else if(expr.getNameAsString().equals(csr.getSourceTestMethod())){
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
            MethodCallExpr clonedKey = entry.getKey().clone();
            entry.getValue().getParameters().forEach(param -> paramTypes.add(param.getTypeAsString()));
            //adjust param types for varArg
            int paramDiffSize = clonedKey.getArguments().size()-paramTypes.size();
            if(paramDiffSize > 0){
                String type = paramTypes.get(paramTypes.size()-1);
                for(int i=0; i<paramDiffSize; i++){
                    paramTypes.add(type);
                }
            }
            clonedKey.removeScope();
            sourceParamTypes.put(clonedKey, paramTypes);
            resolvedCalls.put(replaceClass(clonedKey, csr.getSourceClassName(), csr.getTargetClassName()), entry.getValue());
        }
    }

    private MethodDeclaration getMethodDecl(ResolvedMethodDeclaration rmd){
        final MethodDeclaration[] methodDeclaration = {null};
        ArrayList<String> paramTypes = getMethodParamTypes(rmd);
        String className = rmd.getClassName();
        String path = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getSourceDir()), className+".java");
        if(path != null && !path.contains(File.separator+"test"+File.separator)){
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

    private Set<String> getMethodsCalledInTest(CompilationUnit cu, CodeSearchResults csr){
        Set<String> methodCalls = new HashSet<>();
        cu.findAll(MethodCallExpr.class).forEach(callExpr ->
                methodCalls.add(replaceClass(callExpr, csr.getTargetClassName(), csr.getSourceClassName())));
        return methodCalls;
    }

    private String replaceClass(MethodCallExpr callExpr, String original, String replacement){
        String scope = "";
        if(callExpr.getScope().isPresent()){
            scope = callExpr.getScope().get().toString();
        }
        if(scope.startsWith(original)){
            scope = replacement+scope.substring(original.length());
        }
        String name = callExpr.getNameAsString();
        String arg = callExpr.getArguments().toString().replace(original, replacement);
        arg = "("+arg.substring(1, arg.length()-1)+")";
        if(scope.isEmpty()) return name+arg;
        return scope+"."+name+arg;
    }
}
