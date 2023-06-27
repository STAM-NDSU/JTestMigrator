package stam.testmigration.main;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.apache.commons.lang.StringUtils;
import stam.testmigration.search.CodeSearchResults;
import stam.testmigration.setup.SetupTargetApp;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public class TestCodeCleaner {

    void cleanTestCode(CompilationUnit cu, String targetTestMethod, String targetClassName){
        SetupTargetApp setupTargetApp = new SetupTargetApp();
        removeUnusedClasses(cu, setupTargetApp, targetTestMethod);
        removeDeadMethods(cu);
        removeDeadInnerClasses(cu);
        removeUnresolvedAnnotations(cu);
        removeFieldsNotInTarget(cu, targetClassName);
        removeUnusedImports(cu);
        fixBrokenApis(cu, targetTestMethod);
    }

    //remove source app's unused classes from test code
    private void removeUnusedClasses(CompilationUnit cu, SetupTargetApp setupTargetApp, String targetTestMethod){
        Map<String, String> varNameType = getSourceClassInTest(cu, setupTargetApp);
        List<Statement> statements = getVarNotReferenced(cu, varNameType, targetTestMethod);
        statements.addAll(getClassRefInReceivers(cu, setupTargetApp));
        removeStatements(cu, statements);

        ArrayList<String> classUsages = new ArrayList<>();
        getPotentialUsageOfClass(cu, classUsages);
        int classSize = cu.getTypes().size();
        if(classSize>1){
            for(int i=1; i<classSize; i++){
                if(!classUsages.contains(cu.getType(i).getNameAsString())){
                    cu.getTypes().remove(i);
                }
            }
        }
    }

    private ArrayList<Statement> getClassRefInReceivers(CompilationUnit cu, SetupTargetApp setupTargetApp){
        ArrayList<MethodCallExpr> callExprs = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodCallExpr callExpr, Object arg){
                super.visit(callExpr, arg);
                if(callExpr.getScope().isPresent()){
                    String receiver = callExpr.getScope().get().toString();
                    if(Character.isUpperCase(receiver.charAt(0))){
                        String path = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getSourceDir()), receiver+".java");
                        String targetPath = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getTargetDir()), receiver+".java");
                        if(path != null && path.contains("src") && targetPath == null){
                            callExprs.add(callExpr);
                        }
                    }
                }
            }
        }, null);

        ArrayList<Statement> statements = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodDeclaration declaration, Object arg){
                super.visit(declaration, arg);
                if(declaration.getBody().isPresent()){
                    declaration.getBody().get().getStatements().forEach(statement -> {
                        callExprs.forEach(callExpr -> {
                            if(statement.toString().contains(callExpr.toString())){
                                statements.add(statement);
                            }
                        });
                    });
                }
            }
        }, null);
        return statements;
    }

    private void getPotentialUsageOfClass(CompilationUnit cu, ArrayList<String> classUsage){
        cu.getType(0).accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(FieldDeclaration node, Object arg){
                super.visit(node, arg);
                classUsage.add(node.getElementType().asString());
            }

            @Override
            public void visit(VariableDeclarationExpr node, Object arg){
                super.visit(node, arg);
                classUsage.add(node.getElementType().asString());
            }

            @Override
            public void visit(MethodCallExpr node, Object arg){
                super.visit(node, arg);
                if(node.getScope().isPresent()){
                    classUsage.add(node.getScope().get().toString());
                }
            }

            @Override
            public void visit(ObjectCreationExpr node, Object arg){
                super.visit(node, arg);
                classUsage.add(node.getTypeAsString());
            }
        }, null);
    }

    private void removeStatements(CompilationUnit cu, List<Statement> statements){
        for(Statement statement: statements){
            cu.accept(new VoidVisitorAdapter<Object>() {
                @Override
                public void visit(MethodDeclaration node, Object arg){
                    super.visit(node, arg);
                    if(node.getBody().isPresent()){
                        node.getBody().get().getStatements().remove(statement);
                    }
                }
            }, null);
        }
    }

    //check that variables are not used other than as arguments of the method to be tested
    private List<Statement> getVarNotReferenced(CompilationUnit cu, Map<String, String> variables, String targetTestMethod){
        List<Statement> removeStmt = new ArrayList<>();
        for(Map.Entry<String, String> entry: variables.entrySet()){
            String name = entry.getKey();
            int lineNum = Integer.parseInt(entry.getValue().substring(entry.getValue().lastIndexOf("-")+1));

            final Statement[] stmt = new Statement[1];
            List<Node> nodes = new ArrayList<>();
            cu.accept(new VoidVisitorAdapter<Object>() {
                @Override
                public void visit(MethodDeclaration node, Object arg){
                    super.visit(node, arg);
                    if(node.getBody().isPresent()){
                        for(Statement statement: node.getBody().get().getStatements()){
                            int line = statement.getBegin().get().line;
                            if(!statement.toString().contains(targetTestMethod)){
                                if(line == lineNum){
                                    stmt[0] = statement;
                                }else if(statement.toString().contains(name)){
                                    stmt[0] = null;
                                }
                            }
                        }
                    }
                }
                @Override
                public void visit(FieldDeclaration node, Object arg){
                    super.visit(node, arg);
                    if(node.toString().contains(name))
                        nodes.add(node);
                }
                @Override
                public void visit(AssignExpr node, Object arg){
                    super.visit(node, arg);
                    if(node.getTarget().toString().equals(name)){
                        nodes.add(node);
                        removeStmt.add(StaticJavaParser.parseStatement(node.toString()+";"));
                    }
                }
            }, null);

            if(stmt[0] != null) removeStmt.add(stmt[0]);
            cu.getType(0).getMembers().removeAll(nodes);
        }
        return removeStmt;
    }

    private Map<String, String> getSourceClassInTest(CompilationUnit cu, SetupTargetApp setupTargetApp){
        Map<String, String> varNameType = getVariablesInTest(cu);
        Map<String, String> variables = new HashMap<>();
        for(Map.Entry<String, String> entry: varNameType.entrySet()){
            String varName = entry.getKey();
            String varType = entry.getValue();
            String path = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getSourceDir()), getSourceType(varType));
            String targetPath = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getTargetDir()), getSourceType(varType));
            //some projects might contain jdk-sources. Therefore check the file exists in "src" directory
            if(path != null && path.contains("src") && targetPath == null)
                variables.put(varName, varType);
        }
        return variables;
    }

    private String getSourceType(String varType){
        String sourceType = varType.substring(0, varType.lastIndexOf("-"));
        if(sourceType.contains("."))
            sourceType = sourceType.substring(0, sourceType.indexOf("."))+".java";
        else
            sourceType = sourceType+".java";
        return sourceType;
    }

    private Map<String, String> getVariablesInTest(CompilationUnit cu){
        Map<String, String> varNameType = new HashMap<>();
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(VariableDeclarator node, Object arg){
                super.visit(node, arg);
                if(!node.getTypeAsString().equals("File")){
                    if(node.getBegin().isPresent())
                        varNameType.put(node.getNameAsString(), node.getTypeAsString()+"-"+node.getBegin().get().line);
                }
            }
        }, null);
        return varNameType;
    }

    private void removeDeadMethods(CompilationUnit cu){
        Map<MethodDeclaration, String> helperMethods = getHelperMethodsInTestClass(cu);

        HashSet<MethodDeclaration> methodsCalled = new HashSet<>();
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodDeclaration declaration, Object arg){
                super.visit(declaration, arg);
                String name = declaration.getNameAsString();
                declaration.accept(new VoidVisitorAdapter<Object>() {
                    @Override
                    public void visit(MethodCallExpr callExpr, Object arg){
                        super.visit(callExpr, arg);
                        String callName = callExpr.getNameAsString();
                        if(callExpr.getScope().isEmpty() && helperMethods.containsValue(callName) && !name.equals(callName)){
                            for(Map.Entry<MethodDeclaration, String> set: helperMethods.entrySet()){
                                if(set.getValue().equals(callName)) methodsCalled.add(set.getKey());
                            }
                        }
                    }
                }, null);
            }
        }, null);

        ArrayList<MethodDeclaration> methodsNotCalled = new ArrayList<>();
        for(MethodDeclaration helperMethod: helperMethods.keySet()){
            if(!methodsCalled.contains(helperMethod)){
                methodsNotCalled.add(helperMethod);
            }
        }

        cu.getType(0).getMembers().removeAll(methodsNotCalled);
        //check inner classes
        cu.getType(0).getMembers().forEach(member ->{
            if(member.isClassOrInterfaceDeclaration()){
                methodsNotCalled.forEach(member::remove);
            }
        });
        if(!methodsNotCalled.isEmpty()) removeDeadMethods(cu);
    }

    private Map<MethodDeclaration, String> getHelperMethodsInTestClass(CompilationUnit cu){
        Map<MethodDeclaration, String> helperMethods = new HashMap<>();
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                if(!isTestMethod(node) && !node.getNameAsString().equals("setUp") && !node.getNameAsString().equals("tearDown")){
                    helperMethods.put(node, node.getNameAsString());
                }
            }
        }, null);
        return helperMethods;
    }

    private boolean isTestMethod(MethodDeclaration node){
        boolean testMethod = false;
        if(node.getNameAsString().startsWith("test")){
            testMethod = true;
        }else{
            for(AnnotationExpr expr: node.getAnnotations()){
                if(expr.getNameAsString().equals("Test")){
                    testMethod = true;
                }
            }
        }
        return testMethod;
    }

    public void cleanUnusedCode(CompilationUnit cu){
        removeExtendedTypes(cu);
        removeDeadFields(cu);
        removeDeadInnerClasses(cu);
        checkFieldsNotInTarget(cu);
        removeFloatingComments(cu);
        removeExplicitWrapperMethods(cu);
    }

    private void removeExtendedTypes(CompilationUnit cu){
        NodeList<ClassOrInterfaceType> types = new NodeList<>();
        for(ClassOrInterfaceType classType : Utilities.getExtendedTypes(cu)){
            String path = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getSourceDir()), classType+".java");
            if(path!=null) types.add(classType);
        }
        types.forEach(cu.getType(0)::remove);
    }

    private void removeExplicitWrapperMethods(CompilationUnit cu){
        ArrayList<MethodCallExpr> callExprs = new ArrayList<>();
        ArrayList<String> wrapperMethods = new ArrayList<>(Arrays.asList("booleanValue", "longValue", "intValue", "byteValue", "shortValue", "floatValue", "doubleValue"));
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodCallExpr callExpr, Object arg){
                super.visit(callExpr, arg);
                if(callExpr.getScope().isPresent() && callExpr.getScope().get().isMethodCallExpr()){
                    if(callExpr.getArguments().isEmpty() && wrapperMethods.contains(callExpr.getNameAsString())){
                        callExprs.add(callExpr);
                    }
                }
            }
        }, null);
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodCallExpr callExpr, Object arg){
                super.visit(callExpr, arg);
                for(MethodCallExpr callExpr1: callExprs){
                    if(callExpr == callExpr1){
                        callExpr.replace(callExpr1.getScope().get().asMethodCallExpr());
                    }
                }
            }
        }, null);
    }

    private void removeFloatingComments(CompilationUnit cu){
        cu.getAllContainedComments().stream()
                .filter(comment -> comment.getCommentedNode().isEmpty())
                .collect(Collectors.toList()).forEach(Node::remove);
    }

    private void checkFieldsNotInTarget(CompilationUnit cu){
        ArrayList<String> wrapperTypes = new ArrayList<>(List.of("Boolean", "Byte", "Short", "Character", "Integer", "Long", "Float", "Double", "Math"));
        Map<String, ArrayList<String>> targetTypeVar = getTargetTypeVars(cu);
        ArrayList<String> fieldsFromClasses = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(FieldAccessExpr accessExpr, Object arg){
                super.visit(accessExpr, arg);
                String receiver = accessExpr.getScope().toString();
                if(Character.isUpperCase(receiver.charAt(0)) && !wrapperTypes.contains(receiver)){
                    fieldsFromClasses.add(receiver);
                }else{
                    targetTypeVar.keySet().forEach(key-> {
                        if(targetTypeVar.get(key).contains(receiver)){
                            fieldsFromClasses.add(key);
                        }
                    });
                }
            }
        }, null);
        fieldsFromClasses.forEach(targetClass -> removeFieldsNotInTarget(cu, targetClass));
    }

    private Map<String, ArrayList<String>> getTargetTypeVars(CompilationUnit cu){
        Map<String, ArrayList<String>> targetTypeVar = new HashMap<>();
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(FieldDeclaration node, Object arg){
                super.visit(node, arg);
                Type nodeType = node.getElementType();
                if(!nodeType.isPrimitiveType() && !nodeType.isArrayType() && !nodeType.toString().equals("String")){
                    String type = sanitizeType(node.getElementType().asString());
                    for(VariableDeclarator vd: node.getVariables()){
                        if(targetTypeVar.containsKey(type)){
                            targetTypeVar.get(type).add(vd.getNameAsString());
                        }else{
                            targetTypeVar.put(type, new ArrayList<>(Collections.singletonList(vd.getNameAsString())));
                        }
                    }
                }
            }
            @Override
            public void visit(VariableDeclarationExpr expr, Object arg){
                super.visit(expr, arg);
                Type exprType = expr.getElementType();
                if(!exprType.isPrimitiveType() && !exprType.isArrayType() && !exprType.toString().equals("String")){
                    String type = sanitizeType(expr.getElementType().asString());
                    for(VariableDeclarator vd: expr.getVariables()){
                        if(targetTypeVar.containsKey(type)){
                            targetTypeVar.get(type).add(vd.getNameAsString());
                        }else{
                            targetTypeVar.put(type, new ArrayList<>(Collections.singletonList(vd.getNameAsString())));
                        }
                    }
                }
            }
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                for(Parameter parameter: node.getParameters()){
                    Type paramType = parameter.getType();
                    if(!paramType.isPrimitiveType() && !paramType.isArrayType() && !paramType.toString().equals("String")){
                        String type = sanitizeType(parameter.getTypeAsString());
                        if(targetTypeVar.containsKey(type)){
                            targetTypeVar.get(type).add(parameter.getNameAsString());
                        }else{
                            targetTypeVar.put(type, new ArrayList<>(Collections.singletonList(parameter.getNameAsString())));
                        }
                    }
                }
            }
        }, null);
        return targetTypeVar;
    }

    private String sanitizeType(String type){
        if(type.contains("<")){
            return type.substring(0, type.indexOf("<"));
        }
        return type;
    }

    private void removeFieldsNotInTarget(CompilationUnit cu, String targetClassName){
        ArrayList<String> targetClassVars = new TestModifier().getSourceVariables(cu, targetClassName);
        ArrayList<String> fieldsInTarget = getFieldsInTarget(targetClassName);
        ArrayList<FieldAccessExpr> fields = new ArrayList<>();
        targetClassVars.add(targetClassName);
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(FieldAccessExpr accessExpr, Object arg){
                super.visit(accessExpr, arg);
                String receiver = accessExpr.getScope().toString();
                if(targetClassVars.contains(receiver) || receiver.equals(targetClassName)){
                    if(!fieldsInTarget.contains(accessExpr.getNameAsString())){
                        fields.add(accessExpr);
                    }
                }
            }
        }, null);

        ArrayList<Statement> statements = new ArrayList<>();
        getStatements(fields, statements, cu);

        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                statements.forEach(statement -> {
                    if(node.getBody().isPresent() && node.getBody().get().getStatements().contains(statement)){
                        node.getBody().get().getStatements().remove(statement);
                    }
                });
            }
        }, null);
    }

    private void getStatements(ArrayList<FieldAccessExpr> accessExprs, ArrayList<Statement> statements, CompilationUnit cu){
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                if(node.getBody().isPresent()){
                    for(Statement stmt: node.getBody().get().getStatements()){
                        accessExprs.forEach(expr->{
                            if(stmt.toString().contains(expr.toString())){
                                statements.add(stmt);
                            }
                        });
                    }
                }
            }
        }, null);
    }

    private ArrayList<String> getFieldsInTarget(String targetClassName){
        ArrayList<String> fields = new ArrayList<>();
        String path = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getTargetDir()), targetClassName+".java");
        if(path != null){
            SetupTargetApp.getCompilationUnit(new File(path)).accept(new VoidVisitorAdapter<Object>() {
                @Override
                public void visit(FieldDeclaration declaration, Object arg){
                    super.visit(declaration, arg);
                    for(VariableDeclarator vd: declaration.getVariables()){
                        fields.add(vd.getNameAsString());
                    }
                }
            }, null);
        }
        return fields;
    }

    private void removeDeadFields(CompilationUnit cu){
        ArrayList<FieldDeclaration> fieldsInTest = new ArrayList<>(cu.getType(0).getFields());
        ArrayList<FieldDeclaration> deadFields = new ArrayList<>();
        for(FieldDeclaration field: fieldsInTest){
            if(field.getAnnotations().isEmpty() && StringUtils.countMatches(cu.getType(0).toString(), field.getVariable(0).getNameAsString()) == 1){
                deadFields.add(field);
            }else{
                //findFieldUsage(field, deadFields, cu);
            }
        }
        cu.getType(0).getMembers().removeAll(deadFields);
    }

    private void findFieldUsage(FieldDeclaration field, ArrayList<FieldDeclaration> deadFields, CompilationUnit cu){
        final boolean[] usageFound = {false};
        for(VariableDeclarator vd: field.getVariables()){
            String varName = vd.getNameAsString();
            cu.getType(0).accept(new VoidVisitorAdapter<Object>() {
                @Override
                public void visit(MethodCallExpr node, Object arg){
                    super.visit(node, arg);
                    if(node.getScope().isPresent() && node.getScope().get().toString().equals(varName)){
                        usageFound[0] = true;
                    }
                    for(Expression expression: node.getArguments()){
                        if(expression.toString().equals(varName)){
                            usageFound[0] = true;
                        }
                    }
                }
                @Override
                public void visit(ObjectCreationExpr node, Object arg){
                    super.visit(node, arg);
                    for(Expression expression: node.getArguments()){
                        if(expression.toString().equals(varName)){
                            usageFound[0] = true;
                        }
                    }
                }
            }, null);
        }
        if(!usageFound[0]){
            deadFields.add(field);
        }
    }

    private void removeUnresolvedAnnotations(CompilationUnit cu){
        ArrayList<String> imports = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(ImportDeclaration node, Object arg){
                super.visit(node, arg);
                imports.add(node.getNameAsString());
            }
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                ArrayList<AnnotationExpr> annotations = new ArrayList<>();
                for(AnnotationExpr expr: node.getAnnotations()){
                    if(!hasImport(expr.getNameAsString(), imports)){
                        annotations.add(expr);
                    }
                }
                node.getAnnotations().removeAll(annotations);
            }
        }, null);

        ArrayList<AnnotationExpr> unresolvedAnnotations = new ArrayList<>();
        for(AnnotationExpr expr: cu.getType(0).getAnnotations()){
            if(!hasImport(expr.getNameAsString(), imports)){
                unresolvedAnnotations.add(expr);
            }
        }
        cu.getType(0).getAnnotations().removeAll(unresolvedAnnotations);
    }

    private boolean hasImport(String annotation, ArrayList<String> imports){
        for(String im: imports){
            if(im.contains(annotation)){
                return true;
            }
        }
        return false;
    }

    private void removeDeadInnerClasses(CompilationUnit cu){
        ArrayList<ClassOrInterfaceDeclaration> deadClasses = new ArrayList<>();
        ArrayList<ClassOrInterfaceDeclaration> innerClasses = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(ClassOrInterfaceDeclaration node, Object arg){
                super.visit(node, arg);
                if(!node.isTopLevelType()){
                    innerClasses.add(node);
                    if(node.getMembers().size() == 0){
                        deadClasses.add(node);
                    }
                }
            }
        }, null);

        for(ClassOrInterfaceDeclaration innerClass: innerClasses){
            int size = innerClass.getConstructors().size()+1;
            if(StringUtils.countMatches(cu.getType(0).toString(), innerClass.getNameAsString()) == size && !deadClasses.contains(innerClass)){
                deadClasses.add(innerClass);
            }
        }

        cu.getType(0).getMembers().removeAll(deadClasses);
    }

    void removeOverloadedTestMethod(CompilationUnit cu, int targetParamSize){
        ArrayList<MethodDeclaration> overloadedMethods = new ArrayList<>();
        ArrayList<MethodDeclaration> targetTestMethods = new ArrayList<>();
        String targetMethodName = new CodeSearchResults().getTargetTestMethod();

        if(hasOverloadedMethods(targetMethodName, targetParamSize)){
            cu.accept(new VoidVisitorAdapter<Object>() {
                @Override
                public void visit(MethodDeclaration node, Object arg){
                    super.visit(node, arg);
                    if(isTestMethod(node)){
                        node.accept(new VoidVisitorAdapter<Object>() {
                            @Override
                            public void visit(MethodCallExpr callExpr, Object arg){
                                super.visit(callExpr, arg);
                                if(callExpr.getNameAsString().equals(targetMethodName)){
                                    if(!targetTestMethods.contains(node)){
                                        targetTestMethods.add(node);
                                    }
                                    if(callExpr.getArguments().size() != targetParamSize && !overloadedMethods.contains(node)) {
                                        overloadedMethods.add(node);
                                    }
                                }
                            }
                        }, null);
                    }
                }
            }, null);
        }

        if(targetTestMethods.size() != overloadedMethods.size()){
            cu.getType(0).getMembers().removeAll(overloadedMethods);
        }

        //check inner classes
        cu.getType(0).getMembers().forEach(member ->{
            if(member.isClassOrInterfaceDeclaration()){
                overloadedMethods.forEach(member::remove);
            }
        });
    }

    private boolean hasOverloadedMethods(String targetMethodName, int targetParamSize){
        ArrayList<MethodCallExpr> sourceCallExprs = TestModifier.replacedMethods.get(targetMethodName);
        if(sourceCallExprs != null && sourceCallExprs.size()>1){
            for(MethodCallExpr expr: sourceCallExprs){
                if(expr.getArguments().size() == targetParamSize){
                    return true;
                }
            }
        }
        return false;
    }

    private void removeUnusedImports(CompilationUnit cu){
        NodeList<ImportDeclaration> unusedImports = new NodeList<>();
        cu.getImports().forEach(id ->{
            String importName = id.getNameAsString();
            String lastSymbol = importName.substring(importName.lastIndexOf(".")+1);
            if(!id.isAsterisk() && StringUtils.countMatches(cu.getType(0).toString(), lastSymbol) == 0){
                unusedImports.add(id);
            }
        });
        cu.getImports().removeAll(unusedImports);
    }

    //temp solution: fix broken api call sequence
    //TODO: generalize this task
    private void fixBrokenApis(CompilationUnit cu, String targetTestMethod){
        //for java.util.concurrent.CountDownLatch
        List<Statement> removeStmt = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                //super.visit(node, arg);
                if(node.getBody().isPresent()){
                    for(Statement statement: node.getBody().get().getStatements()){
                        if(!statement.toString().contains(targetTestMethod)){
                            if(statement.toString().contains("CountDownLatch"))
                                removeStmt.add(statement);
                            else if(statement.toString().contains(".countDown()"))
                                removeStmt.add(statement);
                            else if(statement.toString().contains(".await()"))
                                removeStmt.add(statement);
                        }
                    }
                }
            }
        }, null);

        if(removeStmt.size() != 3 && removeStmt.size() != 6){
            cu.accept(new VoidVisitorAdapter<Object>() {
                @Override
                public void visit(MethodDeclaration node, Object arg){
                    super.visit(node, arg);
                    if(node.getBody().isPresent()){
                        node.getBody().get().getStatements().removeAll(removeStmt);
                    }
                }
            }, null);
        }
    }
}
