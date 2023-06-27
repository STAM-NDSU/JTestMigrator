package stam.testmigration.main;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.symbolsolver.utils.SymbolSolverCollectionStrategy;
import com.github.javaparser.utils.ProjectRoot;
import com.github.javaparser.utils.SourceRoot;
import com.google.common.collect.Iterables;
import stam.testmigration.search.CodeSearchResults;
import stam.testmigration.setup.SetupTargetApp;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ClassObjectModifier {
    CompilationUnit cu;
    String sourceClassName, targetClassName, resolvedClassName, sourceTestMethod, targetTestMethod;

    ClassObjectModifier(CompilationUnit cu, String sourceClassName, String targetClassName, String sourceTestMethod, String targetTestMethod){
        this.cu = cu;
        this.sourceClassName = sourceClassName;
        this.targetClassName = targetClassName;
        this.sourceTestMethod = sourceTestMethod;
        this.targetTestMethod = targetTestMethod;
    }

    void replaceClass() {
        cu.accept(new VoidVisitorAdapter<>() {
            @Override
            public void visit(FieldDeclaration node, Object arg) {
                super.visit(node, arg);
                String nodeType = sanitizeNodeType(node.getElementType().toString());
                if (nodeType.equals(sourceClassName)) {
                    replaceFieldType(node, targetClassName);
                }else if(MethodMatcher.sourceTargetClass.containsKey(nodeType)){
                    replaceFieldType(node, Iterables.getOnlyElement(MethodMatcher.sourceTargetClass.get(nodeType)));
                }
            }
            @Override
            public void visit(Parameter node, Object arg){
                super.visit(node, arg);
                String nodeType = sanitizeNodeType(node.getTypeAsString());
                if (nodeType.equals(sourceClassName)) {
                    replaceParamType(node, targetClassName);
                }else if(MethodMatcher.sourceTargetClass.containsKey(nodeType)){
                    replaceParamType(node, Iterables.getOnlyElement(MethodMatcher.sourceTargetClass.get(nodeType)));
                }
            }
            @Override
            public void visit(InstanceOfExpr expr, Object arg){
                super.visit(expr, arg);
                String nodeType = sanitizeNodeType(expr.getTypeAsString());
                if(nodeType.equals(sourceClassName)){
                    replaceInstanceType(expr, targetClassName);
                }else if(MethodMatcher.sourceTargetClass.containsKey(nodeType)){
                    replaceInstanceType(expr, Iterables.getOnlyElement(MethodMatcher.sourceTargetClass.get(nodeType)));
                }
            }
            @Override
            public void visit(VariableDeclarator declarator, Object arg){
                super.visit(declarator, arg);
                String nodeType = sanitizeNodeType(declarator.getTypeAsString());
                if(nodeType.equals(sourceClassName)){
                    replaceVarType(declarator, targetClassName);
                }else if(MethodMatcher.sourceTargetClass.containsKey(nodeType)){
                    replaceVarType(declarator, Iterables.getOnlyElement(MethodMatcher.sourceTargetClass.get(nodeType)));
                }
            }

            @Override
            public void visit(MethodReferenceExpr expr, Object arg){
                super.visit(expr, arg);
                String nodeType = sanitizeNodeType(expr.getScope().toString());
                if(nodeType.equals(sourceClassName)){
                    replaceReferenceType(expr, targetClassName);
                }else if(MethodMatcher.sourceTargetClass.containsKey(nodeType)){
                    replaceReferenceType(expr, Iterables.getOnlyElement(MethodMatcher.sourceTargetClass.get(nodeType)));
                }
            }
        }, null);
        replaceStaticRef();
    }

    private void replaceReferenceType(MethodReferenceExpr expr, String targetType){
        String typeArgument = checkAndGetTypeArgument(expr.getScope().toString(), targetType);
        if(typeArgument != null){
            expr.setScope(new NameExpr().setName(targetType+typeArgument));
        }else{
            expr.setScope(new NameExpr().setName(targetType));
        }
    }

    private void replaceFieldType(FieldDeclaration node, String targetType){
        String classVar = node.getVariables().get(0).toString();
        String typeArgument = checkAndGetTypeArgument(node.getElementType().toString(), targetType);
        VariableDeclarator vd = new VariableDeclarator();
        if(typeArgument != null){
            vd.setType(targetType+typeArgument).setName(classVar);
        }else{
            vd.setType(targetType).setName(classVar);
        }
        FieldDeclaration fd = new FieldDeclaration().addVariable(vd).setModifiers(node.getModifiers());
        node.replace(fd);
    }

    private void replaceParamType(Parameter node, String targetType){
        String typeArgument = checkAndGetTypeArgument(node.getTypeAsString(), targetType);
        if(typeArgument != null){
            node.setType(targetType+typeArgument);
        }else{
            node.setType(targetType);
        }
    }

    private void replaceInstanceType(InstanceOfExpr node, String targetType){
        String typeArgument = checkAndGetTypeArgument(node.getTypeAsString(), targetType);
        if(typeArgument != null){
            node.setType(targetType+typeArgument);
        }else{
            node.setType(targetType);
        }
    }

    private void replaceVarType(VariableDeclarator declarator, String targetType){
        String typeArgument = checkAndGetTypeArgument(declarator.getTypeAsString(), targetType);
        if(typeArgument != null){
            declarator.setType(targetType+typeArgument);
        }else{
            declarator.setType(targetType);
        }
    }

    private String sanitizeNodeType(String nodeType){
        if(nodeType.contains("<") && nodeType.contains(">")){
            return nodeType.substring(0, nodeType.indexOf("<"));
        }
        return nodeType;
    }

    private String checkAndGetTypeArgument(String sourceClassType, String targetClassType){
        if(!sourceClassType.contains("<")){
            return null;
        }

        SetupTargetApp setupTargetApp = new SetupTargetApp();

        String sourcePath = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getSourceDir()), getFileName(sourceClassType));
        String targetPath = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getTargetDir()), getFileName(targetClassType));

        if(sourcePath != null && targetPath != null){
            CompilationUnit sourceCU = SetupTargetApp.getCompilationUnit(new File(sourcePath));
            CompilationUnit targetCU = SetupTargetApp.getCompilationUnit(new File(targetPath));

            int sourceTypeParamSize = sourceCU.getType(0).resolve().getTypeParameters().size();
            int targetTypeParamSize = targetCU.getType(0).resolve().getTypeParameters().size();
            if(sourceTypeParamSize > 0 && sourceTypeParamSize == targetTypeParamSize){
                return sourceClassType.substring(sourceClassType.indexOf("<"), sourceClassType.lastIndexOf(">")+1);
            }
        }
        return null;
    }

    private String getFileName(String className){
        if(className.contains("<")){
            className = className.substring(0, className.indexOf("<"));
        }
        long dotCount = className.chars().filter(ch -> ch == '.').count();
        if(dotCount > 0){
            return className.substring(0, className.indexOf("."))+".java";
        }
        return className+".java";
    }

    //replace source class accessing static methods
    private void replaceStaticRef(){
        boolean isSourceMethodStatic = isMethodStatic(SetupTargetApp.getSourceDir(), sourceClassName, sourceTestMethod);
        boolean isTargetMethodStatic = isMethodStatic(SetupTargetApp.getTargetDir(), targetClassName, targetTestMethod);
        if(isSourceMethodStatic){
            cu.accept(new VoidVisitorAdapter<Object>() {
                @Override
                public void visit(MethodCallExpr node, Object arg){
                    super.visit(node, arg);
                    if(node.getNameAsString().equals(sourceTestMethod) && node.getScope().isPresent() && node.getScope().get().isNameExpr()){
                        if(isTargetMethodStatic){
                            node.getScope().get().replace(new NameExpr().setName(targetClassName));
                        }else{
                            TestModifier.constructorsInTest.add(targetClassName);
                            node.getScope().get().replace(new ObjectCreationExpr().setType(targetClassName));
                        }
                    }else if(node.getNameAsString().equals(sourceTestMethod) && node.getScope().isEmpty()){
                        if(isTargetMethodStatic){
                            node.setScope(new NameExpr(targetClassName));
                        }else{
                            node.setScope(new ObjectCreationExpr().setType(targetClassName));
                            TestModifier.constructorsInTest.add(targetClassName);
                        }
                    }
                }
            }, null);
        }else if(isTargetMethodStatic){
            cu.accept(new VoidVisitorAdapter<Object>() {
                @Override
                public void visit(MethodCallExpr node, Object arg){
                    super.visit(node, arg);
                    if(node.getNameAsString().equals(sourceTestMethod) && node.getScope().isPresent() && node.getScope().get().isNameExpr()){
                        node.getScope().get().replace(new NameExpr().setName(targetClassName));
                    }
                }
            }, null);
        }
    }

    //check whether a method is static
    private boolean isMethodStatic(String dir, String className, String testMethod){
        MethodDeclaration md = getMethodDeclaration(dir, className, testMethod);
        if(md != null){
            return md.isStatic();
        }
        return false;
    }

    MethodDeclaration getMethodDeclaration(String dir, String className, String testMethod){
        final MethodDeclaration[] declaration = {null};
        SetupTargetApp setupTargetApp = new SetupTargetApp();
        String sourcePath = setupTargetApp.findFileOrDir(new File(dir), TestModifier.getFileNameOfInnerClass(className)+".java");
        CompilationUnit sourceCU = SetupTargetApp.getCompilationUnit(new File(sourcePath));
        sourceCU.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                if(node.getNameAsString().equals(testMethod) && node.resolve().getClassName().equals(className)){
                    declaration[0] = node;
                }
            }
        }, null);
        return declaration[0];
    }

    //replace source class object with target class object in test file
    void replaceObject(SetupTargetApp setupTargetApp) {
        resolvedClassName = resolveClassInheritance(setupTargetApp);
        cu.accept(new VoidVisitorAdapter<>() {
            @Override
            public void visit(ObjectCreationExpr node, Object arg) {
                super.visit(node, arg);
                String type = node.getTypeAsString();
                if (sanitizeNodeType(type).equals(resolvedClassName)){
                    new ConstructorMapper().findTargetConstructor(node, targetClassName);
                    if(type.contains("<")){
                        //for parameterized type
                        String parameterizedType = type.substring(type.indexOf("<"));
                        node.setType(targetClassName+parameterizedType);
                    }else{
                        node.setType(targetClassName);
                    }
                }
            }
        }, null);
        //Need to pass parameters later
        TestModifier.constructorsInTest.add(targetClassName);
    }

    public static CompilationUnit getTestCompilationFromSourceApp(){
        SymbolSolverCollectionStrategy symbolSolverCollectionStrategy = new SymbolSolverCollectionStrategy();
        ProjectRoot sourceProjectRoot = symbolSolverCollectionStrategy.collect(new File(SetupTargetApp.getSourceDir()).toPath());
        String testFileName = new CodeSearchResults().getTestFileName();
        String testClassName = testFileName.substring(0, testFileName.lastIndexOf("."));
        for(SourceRoot sourceRoot: sourceProjectRoot.getSourceRoots()){
            try {
                sourceRoot.tryToParse();
            } catch (IOException ioException) {
                ioException.printStackTrace();
            }
            for(CompilationUnit compilationUnit: sourceRoot.getCompilationUnits()){
                if(compilationUnit.getTypes().size()>0 && compilationUnit.getType(0).resolve().getName().equals(testClassName)){
                    return compilationUnit;
                }
            }
        }
        return null;
    }

    //if a sub-class object is assigned to it's super class,
    //class type will be different in object creation and left side of Assign expr
    private String resolveClassInheritance(SetupTargetApp setupTargetApp){
        List<String> finalSourceClassName = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<>() {
            public void visit(ObjectCreationExpr node, Object arg){
                super.visit(node, arg);
                String classType = node.getTypeAsString();
                if(classType.equals(sourceClassName))
                    finalSourceClassName.add(0, sourceClassName);
                else{
                    String path = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getSourceDir()), classType+".java");
                    if(path != null){
                        CompilationUnit cu = SetupTargetApp.getCompilationUnit(new File(path));
                        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(ciNode -> {
                            if(ciNode.getNameAsString().equals(classType))
                                ciNode.getExtendedTypes().forEach(extendType -> {
                                    if(extendType.toString().equals(sourceClassName)){
                                        finalSourceClassName.add(0, classType);
                                    }
                                });
                        });
                    }
                }
            }
        }, null);
        //for static method access
        if(finalSourceClassName.isEmpty())
            finalSourceClassName.add(0, sourceClassName);
        return finalSourceClassName.get(0);
    }

    //remove all the remaining program elements that reference source class
    void removeSourceClassRef(){
        List<Node> nodes = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<>() {
            @Override
            public void visit(FieldDeclaration node, Object arg){
                super.visit(node, arg);
                if(node.toString().contains(sourceClassName))
                    nodes.add(node);
            }
            @Override
            public void visit(ClassOrInterfaceDeclaration node, Object arg){
                super.visit(node, arg);
                if(node.isInnerClass() && node.getImplementedTypes().isNonEmpty()){
                    node.getImplementedTypes().forEach(type -> {
                        if(type.toString().startsWith(sourceClassName))
                            nodes.add(node);
                    });
                }
            }
        }, null);
        cu.getType(0).getMembers().removeAll(nodes);

        checkAndReplacePrivateConstructor();
    }

    void checkAndReplacePrivateConstructor(){
        ArrayList<ObjectCreationExpr> objects = new ArrayList<>();
        getAllObjectsInTest(objects);
        for(ObjectCreationExpr expr: objects){
            String path = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getTargetDir()), expr.getTypeAsString()+".java");
            if(path != null){
                CompilationUnit cUnit = SetupTargetApp.getCompilationUnit(new File(path));
                if(isPrivateConstructor(cUnit, expr.getTypeAsString())){
                    //get a public static method that returns the object
                    MethodDeclaration methodDeclaration = getObjectReturningMethod(cUnit, expr.getTypeAsString());
                    if(methodDeclaration != null){
                        replaceObjectWithMethodCall(expr, methodDeclaration);
                    }else{
                        //remove (invalid) constructor
                        removeConstructor(expr);
                    }
                }
            }
        }
    }

    private void removeConstructor(ObjectCreationExpr expr){
        ArrayList<Statement> statementsToBeRemoved = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodDeclaration declaration, Object arg){
                super.visit(declaration, arg);
                Optional<BlockStmt> blockStmt = declaration.getBody();
                blockStmt.ifPresent(statements -> blockStmt.get().getStatements().forEach(statement -> {
                    if(statement.toString().contains(expr.toString())){
                        statementsToBeRemoved.add(statement);
                    }
                }));
            }
        }, null);

        statementsToBeRemoved.forEach(this::removeStatement);
    }

    private void removeStatement(Statement statement){
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodDeclaration declaration, Object arg){
                super.visit(declaration, arg);
                if(declaration.getBody().isPresent()){
                    declaration.getBody().get().getStatements().remove(statement);
                }
            }
        }, null);
    }

    private void replaceObjectWithMethodCall(ObjectCreationExpr expr, MethodDeclaration methodDeclaration){
        MethodCallExpr callExpr = new MethodCallExpr();
        callExpr.setName(methodDeclaration.getNameAsString());
        callExpr.setScope(new NameExpr(expr.getTypeAsString()));
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(ObjectCreationExpr node, Object arg){
                super.visit(node, arg);
                if(node.equals(expr)){
                    node.replace(callExpr);
                }
            }
        }, null);
    }

    private MethodDeclaration getObjectReturningMethod(CompilationUnit cUnit, String type){
        ArrayList<MethodDeclaration> methods = new ArrayList<>();
        getPublicStaticMethod(cUnit, type, methods);
        //select one method
        if(!methods.isEmpty()){
            for(MethodDeclaration methodDeclaration: methods){
                if(methodDeclaration.getParameters().isEmpty()){
                    return methodDeclaration;
                }
            }
            return methods.get(0);
        }
        return null;
    }

    private void getPublicStaticMethod(CompilationUnit cUnit, String type, ArrayList<MethodDeclaration> methods){
        cUnit.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                if(node.isPublic() && node.isStatic() && type.equals(getReturnType(node))){
                    methods.add(node);
                }
            }
        }, null);
    }

    private String getReturnType(MethodDeclaration node){
        String returnType = node.getTypeAsString();
        if(returnType.contains("<")){
            return returnType.substring(0, returnType.indexOf("<"));
        }
        return returnType;
    }

    private boolean isPrivateConstructor(CompilationUnit cUnit, String type){
        ArrayList<ConstructorDeclaration> constructors = new ArrayList<>(cUnit.findAll(ConstructorDeclaration.class));
        constructors.removeIf(item -> !item.getNameAsString().equals(type));
        if(!constructors.isEmpty() && constructors.get(0).isPrivate()){
            return true;
        }
        return false;
    }

    private void getAllObjectsInTest(ArrayList<ObjectCreationExpr> objects){
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(ObjectCreationExpr node, Object arg){
                super.visit(node, arg);
                if(!objects.contains(node)){
                    objects.add(node);
                }
            }
        }, null);
    }
}
