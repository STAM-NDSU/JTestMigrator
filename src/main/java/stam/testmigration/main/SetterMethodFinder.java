package stam.testmigration.main;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.google.common.collect.Lists;
import stam.testmigration.setup.SetupTargetApp;

import java.io.File;
import java.util.*;

public class SetterMethodFinder {

    void addSetterMethods(CompilationUnit testCU, Type classType){
        CompilationUnit classCU = getCompilationUnit(classType);
        ArrayList<MethodDeclaration> setterMethods = new ArrayList<>();
        if(classCU != null){
            getSetterMethods(classCU, setterMethods);
        }

        Map<Statement, String> classDeclSatements = new HashMap<>();
        getClassDeclStatements(testCU, classType, classDeclSatements);

        Map<Statement, ArrayList<Statement>> callExprsStmts = new LinkedHashMap<>();
        createMethodCallStmt(setterMethods, classDeclSatements, callExprsStmts);

        addCallExprsInTest(testCU, callExprsStmts);

        getInputsForSetters(testCU, classType.asString(), setterMethods);
    }

    private void getInputsForSetters(CompilationUnit testCU, String className,
                                     ArrayList<MethodDeclaration> setterMethods){
        Map<String, List<List<String>>> argsetForMethods = new HashMap<>();
        NodeList<VariableDeclarator> fieldsInTest = getClassVarsFromTest(testCU, className);

        Map<String, NodeList<Parameter>> methodParameters = new HashMap<>();
        for(MethodDeclaration mdNode: setterMethods){
            methodParameters.put(mdNode.getNameAsString(), mdNode.getParameters());
        }

        Map<String, NodeList<VariableDeclarator>> inputsForMethods = new HashMap<>();
        for(Map.Entry<String, NodeList<Parameter>> entry: methodParameters.entrySet()){
            NodeList<VariableDeclarator> varWithoutDuplicates =
                    new NodeList<>(new HashSet<>(new InputTypeFilter().getFilteredInputs(entry.getValue(), getVarsForMethod(fieldsInTest, entry.getKey(), testCU))));
            inputsForMethods.put(entry.getKey(), varWithoutDuplicates);
        }

        for(Map.Entry<String, NodeList<Parameter>> entry: methodParameters.entrySet()){
            String methodName = entry.getKey();
            Map<Integer, List<String>> potentialArgs = new InputTypeConverter().getArguments(entry.getValue(), inputsForMethods.get(methodName),
                    testCU, methodName);
            argsetForMethods.put(methodName, getArgSet(potentialArgs));
        }
        InputInference.argsetForMethods.putAll(argsetForMethods);
    }

    private List<List<String>> getArgSet(Map<Integer, List<String>> potentialArg){
        List<List<String>> temp = new ArrayList<>();
        for(int i=0; i<potentialArg.size(); i++)
            temp.add(potentialArg.get(i));
        List<List<String>> arg = Lists.cartesianProduct(temp);
        return filterArgSet(arg);
    }

    private List<List<String>> filterArgSet(List<List<String>> arg){
        List<List<String>> argSet = new ArrayList<>();
        for(List<String> eachSet: arg){
            if(eachSet.size()>1){
                boolean duplicate = true;
                String value = eachSet.get(0);
                for(String argValue: eachSet){
                    if(!argValue.equals(value))
                        duplicate = false;
                }
                if(!duplicate)
                    argSet.add(eachSet);
            }else{
                argSet.add(eachSet);
            }
        }
        return argSet;
    }

    private void addCallExprsInTest(CompilationUnit testCU, Map<Statement, ArrayList<Statement>> callExprsStmts){
        testCU.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                for(Map.Entry<Statement, ArrayList<Statement>> entry: callExprsStmts.entrySet()){
                    if(node.getBody().isPresent() && node.getBody().get().getStatements().contains(entry.getKey())){
                        for(Statement mCallStmt: entry.getValue()){
                            node.getBody().get().getStatements().addAfter(mCallStmt, entry.getKey());
                        }
                    }
                }
            }
        }, null);
    }

    private void createMethodCallStmt(ArrayList<MethodDeclaration> setterMethods, Map<Statement, String> classDeclSatements,
                                      Map<Statement, ArrayList<Statement>> callExprsStmts){
        for(Map.Entry<Statement, String> entry: classDeclSatements.entrySet()){
            ArrayList<Statement> exprsList = new ArrayList<>();
            for(MethodDeclaration declaration: setterMethods){
                String methodName = declaration.getNameAsString();
                MethodCallExpr methodCallExpr = new MethodCallExpr();
                methodCallExpr.setName(methodName);
                methodCallExpr.setScope(new NameExpr(entry.getValue()));
                exprsList.add(StaticJavaParser.parseStatement(methodCallExpr.toString()+";"));
            }
            Collections.reverse(exprsList);
            callExprsStmts.put(entry.getKey(), exprsList);
        }
    }

    private void getClassDeclStatements(CompilationUnit testCU, Type classType, Map<Statement, String> classDeclSatements){
        testCU.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                if(node.getBody().isPresent()){
                    for(Statement stmt: node.getBody().get().getStatements()){
                        ArrayList<VariableDeclarationExpr> exprs = new ArrayList<>(stmt.findAll(VariableDeclarationExpr.class));
                        if(!exprs.isEmpty() && exprs.get(0).getElementType().toString().equals(classType.toString())){
                            VariableDeclarator varDecl = exprs.get(0).getVariable(0);
                            if(varDecl.getInitializer().isPresent() && varDecl.getInitializer().get().isObjectCreationExpr()){
                                classDeclSatements.put(stmt, varDecl.getNameAsString());
                            }
                        }
                    }
                }
            }
        }, null);
    }

    private void getSetterMethods(CompilationUnit classCU, ArrayList<MethodDeclaration> setterMethods){
        classCU.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                if(node.getNameAsString().startsWith("set")){
                    setterMethods.add(node);
                }
            }
        }, null);
    }

    private CompilationUnit getCompilationUnit(Type classType){
        CompilationUnit classCU = null;
        String path = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getTargetDir()), classType+".java");
        if(path != null){
            File classFile = new File(path);
            classCU = SetupTargetApp.getCompilationUnit(classFile);
        }
        return classCU;
    }

    private NodeList<VariableDeclarator> getClassVarsFromTest(CompilationUnit testCU, String targetClassName){
        NodeList<VariableDeclarator> variableDeclarators = new NodeList<>();
        testCU.accept(new VoidVisitorAdapter<>() {
            @Override
            public void visit(FieldDeclaration node, Object arg){
                super.visit(node, arg);
                //return all fields except target class variable
                if(!node.getElementType().toString().equals(targetClassName))
                    variableDeclarators.addAll(node.getVariables());
            }
        }, null);
        return variableDeclarators;
    }

    private NodeList<VariableDeclarator> getVarsForMethod(NodeList<VariableDeclarator> fieldsInTest, String methodName,
                                                          CompilationUnit testCU){
        NodeList<VariableDeclarator> variables = new NodeList<>(fieldsInTest);
        List<MethodDeclaration> methodDeclaration = new ArrayList<>();
        testCU.accept(new VoidVisitorAdapter<>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                node.accept(new VoidVisitorAdapter<>() {
                    @Override
                    public void visit(MethodCallExpr callExpr, Object arg){
                        super.visit(callExpr, arg);
                        if(callExpr.getNameAsString().equals(methodName))
                            methodDeclaration.add(node);
                    }
                }, null);
            }
        }, null);
        variables.addAll(getLocalVars(methodDeclaration));
        return variables;
    }

    private NodeList<VariableDeclarator> getLocalVars(List<MethodDeclaration> methodDeclaration){
        NodeList<VariableDeclarator> localVars = new NodeList<>();
        for (MethodDeclaration mdNode: methodDeclaration){
            mdNode.accept(new VoidVisitorAdapter<Object>() {
                @Override
                public void visit(VariableDeclarator node, Object arg){
                    super.visit(node, arg);
                    localVars.add(node);
                }
            }, null);
        }
        return localVars;
    }
}
