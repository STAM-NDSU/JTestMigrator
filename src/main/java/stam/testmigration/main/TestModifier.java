package stam.testmigration.main;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.apache.commons.lang.StringUtils;
import stam.testmigration.search.CodeSearchResults;
import smr.testmigration.setup.*;
import stam.testmigration.setup.CodeCoverageRunner;
import stam.testmigration.setup.GradleTestFilterUpdater;
import stam.testmigration.setup.PomTestFilterUpdater;
import stam.testmigration.setup.SetupTargetApp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.NumberFormat;
import java.util.*;

public class TestModifier {
    SetupTargetApp setupTargetApp = new SetupTargetApp();
    CodeSearchResults searchResults = new CodeSearchResults();
    static NodeList<FieldDeclaration> fieldsToAdd = new NodeList<>();
    static Map<String, ArrayList<MethodCallExpr>> replacedMethods = new HashMap<>();
    static ArrayList<String> constructorsInTest = new ArrayList<>();
    private double firstCoverage, secondCoverage;

    void modifyTest() {
        String sourceFileName = searchResults.getSourceFileName();
        String sourceClassName = searchResults.getSourceClassName();

        String targetFileName = searchResults.getTargetFileName();
        String targetClassName = searchResults.getTargetClassName();

        String testFileName = SetupTargetApp.getTestFileNameInTarget();
        String path = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getTargetDir()), testFileName);
        File testFile = new File(path);

        String sourceTestMethod = searchResults.getSourceTestMethod();
        String targetTestMethod = searchResults.getTargetTestMethod();

        CodeCoverageRunner runner = new CodeCoverageRunner();
        firstCoverage = runner.runCodeCoverage(true);
        handleCoverageResult(true, firstCoverage);

        //CompilationUnit cu = SetupTargetApp.getTestCompilationFromTargetApp(testFile);
        CompilationUnit cu = SetupTargetApp.getCompilationUnit(testFile);

        removeUnrelatedTests(cu, sourceTestMethod);
        replacePackage(cu, targetFileName);
        removeSourceImports(cu, sourceFileName);
        modifyConfigAnnotation(cu);

        new ConstructorMapper().mapConstructors(cu);
        ClassObjectModifier classObjModifier = new ClassObjectModifier(cu, sourceClassName, targetClassName, sourceTestMethod, targetTestMethod);
        classObjModifier.replaceClass();
        classObjModifier.replaceObject(setupTargetApp);
        classObjModifier.removeSourceClassRef();
        commitChanges(cu, testFile);
        new MethodMatcher().matchMethods(cu);
        moveStaticFields(cu, sourceClassName);
        replaceMethodCall(cu, sourceTestMethod, targetTestMethod);
        new MethodCallResolver().resolveCalls();
        classObjModifier.replaceClass();
        removeMethodCall(cu, sourceClassName, targetClassName);
        new ExceptionHandler(cu, targetTestMethod, targetClassName).addException();
        //declare input variables that do not exist in test class
        cu.getType(0).getMembers().addAll(0, fieldsToAdd);

        commitChanges(cu, testFile);
        new ReturnTypeAdjuster().adjustReturnType(cu, testFile, targetTestMethod);
        TestHelper testHelper = new TestHelper();
        testHelper.checkHelper(cu, sourceClassName);
        testHelper.moveResources(cu);
        new TestCodeCleaner().cleanTestCode(cu, targetTestMethod, targetClassName);
        commitChanges(cu, testFile);
        new TypeReplacer().replaceSimilarTypes(cu);
        InputInference inputInference = new InputInference(cu, testFile, sourceClassName, sourceTestMethod,
                targetClassName, targetTestMethod, setupTargetApp);
        inputInference.inferInputs();
        new TestCodeCleaner().cleanUnusedCode(cu);
        commitChanges(cu, testFile);
        boolean migrationSuccess = runMigratedTest();
        removeAddedTestFilter();

        if(migrationSuccess){
            secondCoverage = runner.runCodeCoverage(false);
            handleCoverageResult(false, secondCoverage);
        }else{
            //Migration fails, no new code coverage.
            System.out.println("Could not calculate code coverage.");
        }
    }

    //remove test methods that are not intended to be tested
    private void removeUnrelatedTests(CompilationUnit cu, String sourceTestMethod) {
        NodeList<MethodDeclaration> mdTestNodes = new NodeList<>();
        NodeList<MethodDeclaration> mdSourceNodes = new NodeList<>();
        ArrayList<String> helperTargetTestMethods = getHelperWithSourceTestMethodCall(cu, sourceTestMethod);
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodDeclaration node, Object arg) {
                super.visit(node, arg);
                //filter test methods
                if(isTestMethod(node)) {
                    mdTestNodes.add(node);
                    //take the test if the name matches exactly
                    if(getName(node.getNameAsString()).equals(getSourceTestName(sourceTestMethod)) && !mdSourceNodes.contains(node)){
                        mdSourceNodes.add(node);
                    }else{
                        checkMethodCallInTest(node, sourceTestMethod, helperTargetTestMethods, mdSourceNodes);
                    }
                }
            }
        }, null);

        ArrayList<MethodDeclaration> testMethods = filterTestByName(mdSourceNodes, sourceTestMethod);
        NodeList<MethodDeclaration> notRequiredTests = getNotRequiredTests(testMethods, mdTestNodes, mdSourceNodes);
        removeNotRequiredTests(cu, notRequiredTests);
    }

    //take the test if the test calls the source method
    private void checkMethodCallInTest(MethodDeclaration node, String sourceTestMethod,
                                       ArrayList<String> helperTargetTestMethods, NodeList<MethodDeclaration> mdSourceNodes){
        node.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodCallExpr mc, Object arg) {
                super.visit(mc, arg);
                //filter test methods containing methods to be replaced
                if((mc.getNameAsString().equals(sourceTestMethod) || helperTargetTestMethods.contains(mc.getNameAsString()))
                        && !mdSourceNodes.contains(node)){
                    mdSourceNodes.add(node);
                }
            }
        }, null);
    }

    private void removeNotRequiredTests(CompilationUnit cu, NodeList<MethodDeclaration> notRequiredTests){
        cu.getType(0).getMembers().removeAll(notRequiredTests);
        //check inner classes
        cu.getType(0).getMembers().forEach(member ->{
            if(member.isClassOrInterfaceDeclaration()){
                notRequiredTests.forEach(member::remove);
            }
        });
    }

    private NodeList<MethodDeclaration> getNotRequiredTests(ArrayList<MethodDeclaration> testMethods,
                                                            NodeList<MethodDeclaration> mdTestNodes, NodeList<MethodDeclaration> mdSourceNodes){
        NodeList<MethodDeclaration> unnecessaryTests = new NodeList<>();
        if(testMethods.isEmpty()){
            for(MethodDeclaration md : mdTestNodes){
                if(!mdSourceNodes.contains(md)){
                    unnecessaryTests.add(md);
                }
            }
        }else{
            for(MethodDeclaration md : mdTestNodes){
                if(!testMethods.contains(md)) {
                    unnecessaryTests.add(md);
                }
            }
        }
        return unnecessaryTests;
    }

    private String getName(String testName){
        if(testName.startsWith("test")){
            return testName.substring(4).toLowerCase();
        }
        return testName.toLowerCase();
    }

    private ArrayList<MethodDeclaration> filterTestByName(NodeList<MethodDeclaration> mdSourceNodes, String sourceTestMethod){
        ArrayList<MethodDeclaration> testMethods = new ArrayList<>();
        for(MethodDeclaration declaration: mdSourceNodes){
            String testMethodName = StringUtils.lowerCase(declaration.getNameAsString());
            if(testMethodName.contains(getSourceTestName(sourceTestMethod))){
                testMethods.add(declaration);
            }
        }
        return testMethods;
    }

    private String getSourceTestName(String sourceTestMethod){
        String sourceTestName = StringUtils.lowerCase(sourceTestMethod);
        //TODO: use camel case to extract test name string
        if(sourceTestName.startsWith("getas")){
            sourceTestName = sourceTestName.substring(5);
        }else if(sourceTestName.startsWith("set") || sourceTestName.startsWith("get")){
            sourceTestName = sourceTestName.substring(3);
        }else if(sourceTestName.startsWith("is")){
            sourceTestName = sourceTestName.substring(2);
        }
        return sourceTestName;
    }

    private ArrayList<String> getHelperWithSourceTestMethodCall(CompilationUnit cu, String sourceTestMethod){
        ArrayList<String> methodNames = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                if(!isTestMethod(node)){
                    node.accept(new VoidVisitorAdapter<Object>() {
                        @Override
                        public void visit(MethodCallExpr callExpr, Object arg){
                            super.visit(callExpr, arg);
                            if(callExpr.getNameAsString().equals(sourceTestMethod)){
                                methodNames.add(node.getNameAsString());
                            }
                        }
                    }, null);
                }
            }
        }, null);
        return methodNames;
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

    //replace source package with target package in test class
    void replacePackage(CompilationUnit cu, String fileName) {
        String packageName = setupTargetApp.getPackageName(fileName, SetupTargetApp.getTargetDir());
        cu.setPackageDeclaration(packageName);
    }

    //remove imports related to source class from test class
    void removeSourceImports(CompilationUnit cu, String sourceFileName) {
        String packageName = setupTargetApp.getPackageName(sourceFileName, SetupTargetApp.getSourceDir());
        int index = packageName.indexOf(".", packageName.indexOf(".") + 1);
        String packageString = (index == -1) ? packageName : packageName.substring(0, index);

        List<ImportDeclaration> idNodes = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(ImportDeclaration node, Object arg) {
                super.visit(node, arg);
                if(node.getNameAsString().contains(packageString))
                    idNodes.add(node);
            }
        }, null);

        for(ImportDeclaration id : idNodes) id.remove();
    }

    //modify @config annotation used to run Robolectric test
    private void modifyConfigAnnotation(CompilationUnit cu){
        if(cu.getType(0).getAnnotationByName("Config").isPresent()){
            NodeList<MemberValuePair> newPairs = new NodeList<>();
            newPairs.add(new MemberValuePair().setName("manifest").setValue(new NameExpr().setName("Config.NONE")));
            cu.getType(0).getAnnotationByName("Config").get().asNormalAnnotationExpr().setPairs(newPairs);
        }
    }

    //move static fields from source class to test class if the test class access them
    private void moveStaticFields(CompilationUnit cu, String sourceClassName) {
        NodeList<FieldDeclaration> staticSourceFD = getStaticFields(sourceClassName);
        NodeList<FieldDeclaration> staticFields = new NodeList<>();
        cu.accept(new VoidVisitorAdapter<>() {
            @Override
            public void visit(FieldAccessExpr node, Object arg) {
                super.visit(node, arg);
                //get static field access from test class
                if (node.getChildNodes().get(0).toString().equals(sourceClassName)) {
                    for (FieldDeclaration fd : staticSourceFD) {
                        for (VariableDeclarator vd : fd.getVariables()) {
                            if (vd.getNameAsString().equals(node.getNameAsString())) {
                                staticFields.add(fd);
                                //remove static class reference
                                FieldAccessExpr faNode = new FieldAccessExpr();
                                faNode.setName(node.getNameAsString());
                                node.replace(faNode);
                            }
                        }
                    }
                }
            }
        }, null);
        //add static field in test class
        fieldsToAdd.addAll(staticFields);
    }

    //get static fields of source class
    NodeList<FieldDeclaration> getStaticFields(String sourceClassName) {
        NodeList<FieldDeclaration> staticSourceFD = new NodeList<>();
        String path = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getSourceDir()), getFileNameOfInnerClass(sourceClassName)+".java");
        CompilationUnit cu = SetupTargetApp.getCompilationUnit(new File(path));
        cu.accept(new VoidVisitorAdapter<>() {
            @Override
            public void visit(FieldDeclaration node, Object arg) {
                super.visit(node, arg);
                if (node.isStatic())
                    staticSourceFD.add(node);
            }
        }, null);
        return staticSourceFD;
    }

    //replace source method with target method in the test class
    private void replaceMethodCall(CompilationUnit cu, String sourceTestMethod, String targetTestMethod) {
        Map<MethodCallExpr, String> objectsRequired = new HashMap<>();
        cu.accept(new VoidVisitorAdapter<>() {
            @Override
            public void visit(MethodCallExpr node, Object arg) {
                super.visit(node, arg);
                String name = node.getNameAsString();
                MethodCallExpr callExpr = node.clone();
                if (name.equals(sourceTestMethod) && !MethodMatcher.helperCallExprs.contains(callExpr)){
                    node.setName(targetTestMethod);
                    storeReplacedMethod(targetTestMethod, callExpr);
                }else if(MethodMatcher.similarMethods.containsKey(name) && !name.equals(targetTestMethod) && !MethodMatcher.helperCallExprs.contains(callExpr)
                        && !MethodMatcher.similarMethods.get(name).equals(sourceTestMethod) && !MethodMatcher.similarMethods.get(name).equals(targetTestMethod)){
                    node.setName(MethodMatcher.similarMethods.get(name));
                    storeReplacedMethod(MethodMatcher.similarMethods.get(name), callExpr);
                    checkStaticMethods(node, name, objectsRequired, cu);
                }
            }
        }, null);
        for(Map.Entry<MethodCallExpr, String> entry: objectsRequired.entrySet()){
            String name = Character.toLowerCase(entry.getValue().charAt(0))+entry.getValue().substring(1);
            addObjectCreation(entry.getKey(), entry.getValue(), name, cu);
        }
    }

    private void storeReplacedMethod(String targetMethod, MethodCallExpr replacedSourceMethod){
        if(!replacedMethods.containsKey(targetMethod)){
            replacedMethods.put(targetMethod, new ArrayList<>(Collections.singletonList(replacedSourceMethod)));
        }else{
            replacedMethods.get(targetMethod).add(replacedSourceMethod);
        }
    }

    private void checkStaticMethods(MethodCallExpr callExpr, String beforeReplacedName, Map<MethodCallExpr, String> objectsRequired,
                                    CompilationUnit cu){
        MethodDeclaration sourceMethod = null, targetMethod = null;
        for(MethodDeclaration declaration: MethodMatcher.similarMethodDecl.keySet()){
            if(declaration.getNameAsString().equals(beforeReplacedName)){
                sourceMethod = declaration;
                targetMethod = MethodMatcher.similarMethodDecl.get(declaration);
            }
        }

        boolean sourceStatic = Objects.requireNonNull(sourceMethod).isStatic();
        boolean targetStatic = Objects.requireNonNull(targetMethod).isStatic();
        String targetClassName = MethodMatcher.targetMethodAndClass.get(targetMethod.getNameAsString());

        if(sourceStatic && !targetStatic){
            String name = getReceiverIfPresent(callExpr, targetClassName, cu);
            if(name == null){
                name = Character.toLowerCase(targetClassName.charAt(0))+targetClassName.substring(1);
                objectsRequired.put(callExpr, targetClassName);
            }
            addOrReplaceReceiver(callExpr, name);
        }else if(targetStatic){
            addOrReplaceReceiver(callExpr, targetClassName);
        }

        addImport(targetMethod, cu);
    }

    private String getReceiverIfPresent(MethodCallExpr callExpr, String targetClass, CompilationUnit cu){
        ArrayList<MethodDeclaration> declarations = getMethodDeclarations(callExpr, cu);
        final String[] receiver = {null};
        final boolean[] objectPresent = {false};
        for(MethodDeclaration declaration : declarations){
            declaration.accept(new VoidVisitorAdapter<Object>() {
                @Override
                public void visit(VariableDeclarator declarator, Object arg){
                    super.visit(declarator, arg);
                    if(declarator.getTypeAsString().equals(targetClass)){
                        receiver[0] = declarator.getNameAsString();
                    }
                }
                @Override
                public void visit(ObjectCreationExpr expr, Object arg){
                    super.visit(expr, arg);
                    if(expr.getTypeAsString().equals(targetClass)){
                        objectPresent[0] = true;
                    }
                }
            }, null);
        }
        if(!objectPresent[0]){
            return new ObjectCreationExpr().setType(targetClass).toString();
        }
        return receiver[0];
    }

    private ArrayList<MethodDeclaration> getMethodDeclarations(MethodCallExpr callExpr, CompilationUnit cu){
        ArrayList<MethodDeclaration> declarations = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodDeclaration declaration, Object arg){
                super.visit(declaration, arg);
                declaration.accept(new VoidVisitorAdapter<Object>() {
                    @Override
                    public void visit(MethodCallExpr callExpr1, Object arg){
                        super.visit(callExpr1, arg);
                        if(callExpr.equals(callExpr1)){
                            declarations.add(declaration);
                        }
                    }
                }, null);
            }
        }, null);
        return declarations;
    }

    private void addOrReplaceReceiver(MethodCallExpr callExpr, String receiver){
        if(callExpr.getScope().isPresent()){
            callExpr.getScope().get().replace(new NameExpr().setName(receiver));
        }else{
            callExpr.setScope(new NameExpr(receiver));
        }
    }

    private void addImport(MethodDeclaration targetMethod, CompilationUnit cu){
        String qualifiedName = targetMethod.findCompilationUnit().get().getType(0).getFullyQualifiedName().get();
        cu.addImport(qualifiedName);
    }

    private void addObjectCreation(MethodCallExpr callExpr, String classType, String classVarName, CompilationUnit cu){
        NodeList<VariableDeclarator> variableDeclarators = new NodeList<>();
        variableDeclarators.add(new VariableDeclarator().setName(classVarName).setType(classType));
        VariableDeclarationExpr varDeclarationExpr = new VariableDeclarationExpr().setVariables(variableDeclarators);
        ObjectCreationExpr creationExpr = new ObjectCreationExpr().setType(classType);
        AssignExpr assignExpr = new AssignExpr().setTarget(varDeclarationExpr).setValue(creationExpr);
        Statement statement = StaticJavaParser.parseStatement(assignExpr.toString()+";");
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                Statement callStmt = null;
                if(node.getBody().isPresent()){
                    for(Statement stmt: node.getBody().get().getStatements()){
                        if(stmt.toString().contains(callExpr.toString())){
                            callStmt = stmt;
                            break;
                        }
                    }
                }
                if(callStmt != null){
                    if(!isObjectPresent(classType, node)){
                        node.getBody().get().getStatements().addBefore(statement, callStmt);
                    }
                }
            }
        }, null);
    }

    private boolean isObjectPresent(String classType, MethodDeclaration node){
        boolean present = false;
        if(node.getBody().isPresent()){
            for(Statement stmt: node.getBody().get().getStatements()){
                if(stmt.isExpressionStmt() && stmt.asExpressionStmt().getExpression().toString().contains(classType)){
                    present = true;
                }
            }
        }
        return present;
    }

    ArrayList<String> getSourceVariables(CompilationUnit cu, String targetClassName){
        ArrayList<String> sourceVariables = new ArrayList<>();
        String finalTargetClassName = sanitizeType(targetClassName);
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(FieldDeclaration node, Object arg){
                super.visit(node, arg);
                if(sanitizeType(node.getElementType().asString()).equals(finalTargetClassName)){
                    for(VariableDeclarator vd: node.getVariables()){
                        sourceVariables.add(vd.getNameAsString());
                    }
                }
            }
            @Override
            public void visit(VariableDeclarationExpr expr, Object arg){
                super.visit(expr, arg);
                if(sanitizeType(expr.getElementType().asString()).equals(finalTargetClassName)){
                    for(VariableDeclarator vd: expr.getVariables()){
                        sourceVariables.add(vd.getNameAsString());
                    }
                }
            }
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                for(Parameter parameter: node.getParameters()){
                    if(sanitizeType(parameter.getTypeAsString()).equals(finalTargetClassName)){
                        sourceVariables.add(parameter.getNameAsString());
                    }
                }
            }
        }, null);
        return sourceVariables;
    }

    private String sanitizeType(String type){
        if(type.contains("<")){
            return type.substring(0, type.indexOf("<"));
        }
        return type;
    }

    //remove method calls from test class if the methods do not exist in target class
    private void removeMethodCall(CompilationUnit cu, String sourceClassName, String targetClassName){

        List<String> classVariables = getClassVariables(cu, targetClassName);
        List<String> calledMethodNames = getCalledMethods(cu, classVariables, sourceClassName, targetClassName);
        NodeList<Statement> statements = getMethodCallStatements(cu, calledMethodNames);
        cu.accept(new VoidVisitorAdapter<>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                statements.forEach(statement -> {
                    for(Statement stmt: decomposeTryBlockIfPresent(statement)){
                        //TODO: remove a statement from try block
                        if(node.getBody().isPresent()){
                            if(node.getBody().get().getStatements().contains(stmt)){
                                node.getBody().get().getStatements().remove(stmt);
                            }
                        }
                    }
                });
            }
        }, null);
    }

    private ArrayList<Statement> decomposeTryBlockIfPresent(Statement statement){
        ArrayList<Statement> stmts = new ArrayList<>();
        if(statement.isTryStmt()){
            stmts.addAll(statement.asTryStmt().getTryBlock().getStatements());
        }else{
            stmts.add(statement);
        }
        return stmts;
    }

    //get all method call names that refer to one of the target class variable
    private List<String> getCalledMethods(CompilationUnit cu, List<String> classVariables,
                                          String sourceClassName, String targetClassName){
        List<String> calledMethods = new ArrayList<>();
        List<String> methodsInTargetClass = getTargetMethods(targetClassName);
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodCallExpr node, Object arg){
                super.visit(node, arg);
                if(!MethodCallResolver.javaAPIs.contains(node) && node.getScope().isPresent()
                        && node.getScope().get().isNameExpr()){
                    String scope = node.getScope().get().toString();
                    if(classVariables.contains(scope) || scope.equals(sourceClassName))
                        if(!methodsInTargetClass.contains(node.getNameAsString()))
                            calledMethods.add(node.getNameAsString());
                }
            }
        }, null);
        return calledMethods;
    }

    //get all method names called in the target class
    private List<String> getTargetMethods(String targetClassName){
        List<String> targetMethodNames = new ArrayList<>();
        String targetPath = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getTargetDir()), getFileNameOfInnerClass(targetClassName)+".java");
        CompilationUnit targetCU = SetupTargetApp.getCompilationUnit(new File(targetPath));
        targetCU.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                targetMethodNames.add(node.getNameAsString());
            }
        }, null);

        for(MethodDeclaration declaration: new MethodMatcher().getMethodsFromExtendedClass(targetCU)){
            targetMethodNames.add(declaration.getNameAsString());
        }

        return targetMethodNames;
    }

    //get all variable names of target class in test
    private List<String> getClassVariables(CompilationUnit cu, String targetClassName){
        List<String> classVariables = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(FieldDeclaration node, Object arg){
                super.visit(node, arg);
                if(node.getElementType().asString().equals(targetClassName))
                    node.getVariables().forEach(var -> classVariables.add(var.getNameAsString()));
            }
            public void visit(VariableDeclarator node, Object arg){
                super.visit(node, arg);
                if(node.getTypeAsString().equals(targetClassName))
                    classVariables.add(node.getNameAsString());
            }
        }, null);

        return classVariables;
    }

    //get method call statements to be removed
    private NodeList<Statement> getMethodCallStatements(CompilationUnit cu, List<String> calledMethodNames){
        NodeList<Statement> statements = new NodeList<>();
        cu.accept(new VoidVisitorAdapter<>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                if(node.getBody().isPresent()){
                    node.getBody().get().getStatements().forEach(statement -> {
                        ArrayList<String> callInStmt = new ArrayList<>();
                        for(Statement stmt: decomposeTryBlockIfPresent(statement)){
                            stmt.findAll(MethodCallExpr.class).forEach(callExpr -> callInStmt.add(callExpr.getNameAsString()));
                            calledMethodNames.forEach(name -> {
                                if(callInStmt.contains(name)){
                                    statements.add(stmt);
                                }
                            });
                        }
                    });
                }
            }
        }, null);
        return statements;
    }

    public static String getFileNameOfInnerClass(String className){
        CodeSearchResults results = new CodeSearchResults();
        if(className.contains(".")){
            return className.substring(0, className.indexOf("."));
        }else if(className.equals(results.getSourceClassName())){
            String fileName = results.getSourceFileName();
            return fileName.substring(0, fileName.lastIndexOf("."));
        }
        return className;
    }

    private void removeAddedTestFilter(){
        if(SetupTargetApp.getBuildType().equals("gradle")){
            new GradleTestFilterUpdater().removeTestFilter();
        }else if(SetupTargetApp.getBuildType().equals("maven")){
            new PomTestFilterUpdater().removeTestFilter();
        }
    }



    private boolean runMigratedTest(){
        TestRunner testRunner = new TestRunner();
        testRunner.firstTestRun = false;
        boolean testFailed = true;
        int[] errors = testRunner.runTest();
        if(errors[0] == 1){
            testFailed = false;
            //Could not migrate the test due to compile errors
            System.out.println("Test Migration Complete. Calculating Code Coverage...");
        }
        if(errors[1] == 0){
            testFailed = false;
            //Test migrated successfully
            System.out.println("Test Migration Complete. Calculating Code Coverage...");
        }
        //Test migrated successfully, but does not pass
        if(testFailed) System.out.println("Test Migration Complete. Calculating Code Coverage...");

        if(errors[1] == 0 || testFailed){
            return true;
        }else{
            return false;
        }
    }

    //commit changes to test file
    public void commitChanges(CompilationUnit cu, File testFile) {
        byte[] store = cu.toString().getBytes(StandardCharsets.UTF_8);
        try {
            Files.write(testFile.toPath(), store, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleCoverageResult(boolean first, double result) {
        NumberFormat defaultFormat = NumberFormat.getPercentInstance();
        defaultFormat.setMinimumFractionDigits(2);
        if(first){
            if(result != -1){
                System.out.println("Code coverage before migration: " + defaultFormat.format(result));
            } else {
                System.out.println("Cannot get the coverage result before migration.");
            }
        } else {
            if(result != -1 && firstCoverage != -1){
                System.out.println("Code coverage after migration: " + defaultFormat.format(result));
                System.out.println("Increased code coverage by: " + defaultFormat.format(result - firstCoverage));
            }else if(result != -1 && firstCoverage == -1){
                System.out.println("Code coverage after migration: " + defaultFormat.format(result));
                System.out.println("Cannot get the increased code coverage.");
            }else{
                System.out.println("Cannot get the coverage result after migration.");
                System.out.println("Cannot get the increased code coverage.");
            }
        }
    }

}
