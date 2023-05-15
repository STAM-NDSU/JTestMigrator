package stam.testmigration.main;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.google.common.collect.Lists;
import org.apache.commons.lang.StringUtils;
import stam.testmigration.search.CodeSearchResults;
import stam.testmigration.setup.SetupTargetApp;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class InputInference {
    CompilationUnit cu;
    File testFile;
    SetupTargetApp setupTargetApp;
    Map<String, ArrayList<Expression>> sourceArg = new HashMap<>();
    Map<ObjectCreationExpr, ArrayList<Expression>> sourceArgConstructor = new HashMap<>();
    String sourceClassName, sourceTestMethod, targetClassName, targetTestMethod;
    static Map<String, List<List<String>>> argsetForMethods = new HashMap<>();
    static int targetParamSize;
    boolean varArgInTarget = false;

    InputInference(CompilationUnit cu, File testFile, String sourceClassName, String sourceTestMethod, String targetClassName,
                   String targetTestMethod, SetupTargetApp setupTargetApp){
        this.cu = cu;
        this.testFile = testFile;
        this.setupTargetApp = setupTargetApp;
        this.sourceClassName = sourceClassName;
        this.sourceTestMethod = sourceTestMethod;
        this.targetClassName = targetClassName;
        this.targetTestMethod = targetTestMethod;
    }

    void inferInputs(){
        List<List<List<String>>> argset = new ArrayList<>();
        NodeList<VariableDeclarator> fieldsInTest = getClassVarsFromTest();
        InputTypeFilter typeFilter = new InputTypeFilter();
        InputTypeConverter typeConverter = new InputTypeConverter();

        argsetForMethods.putAll(getArgSetForMethods(typeFilter, typeConverter, fieldsInTest));
        for(Map.Entry<String, List<List<String>>> entry: argsetForMethods.entrySet()){
            argset.add(entry.getValue());
        }

        ArrayList<String> methodNames = new ArrayList<>(argsetForMethods.keySet());
        Map<String, List<List<String>>> argsetForConstructors = getArgSetForConstructors(typeFilter, typeConverter, fieldsInTest);
        for(Map.Entry<String, List<List<String>>> entry: argsetForMethods.entrySet()){
            if(!methodNames.contains(entry.getKey())){
                argset.add(entry.getValue());
            }
        }
        for(Map.Entry<String, List<List<String>>> entry: argsetForConstructors.entrySet()){
            argset.add(entry.getValue());
        }

        if(hasNoVarArgInTargetMethod()){
            new TestCodeCleaner().removeOverloadedTestMethod(cu, targetParamSize);
        }

        //select an arg set for constructors and methods, and run the test
        TestRunner testRunner = new TestRunner();
        List<List<List<String>>> argSets = Lists.cartesianProduct(argset);

        long startTime = System.currentTimeMillis();
        if(argSets.isEmpty()){
            runMigratedTestsOnce(testRunner, argsetForConstructors);
        }
        for(List<List<String>> set: argSets) {
            Map<String, List<String>> args = selectInput(set, argsetForMethods, argsetForConstructors);
            System.out.println("Test Input: "+args);

            long elapsedTime = System.currentTimeMillis() - startTime;
            if(elapsedTime>getTimeOut()){
                //Could not migrate the test due to timeout
                System.out.println("Test Migration Complete. Calculating Code Coverage...");
                break;
            }

            int[] errors = testRunner.runTest();
            //could not resolve compile error
            if(errors[0] == 1){
                break;
            }
            //Test migrated successfully
            if(errors[1] == 0){
                break;
            }
        }
        argsetForMethods.clear();
    }

    private void runMigratedTestsOnce(TestRunner testRunner, Map<String, List<List<String>>> argsetForConstructors){
        Map<String, List<String>> args = selectInput(new ArrayList<>(), argsetForMethods, argsetForConstructors);
        System.out.println("Test Input: "+args);
        testRunner.runTest();
    }

    private Map<String, List<List<String>>> getArgSetForConstructors(InputTypeFilter typeFilter, InputTypeConverter typeConverter,
                                                                     NodeList<VariableDeclarator> fieldsInTest){
        Map<String, List<List<String>>> argsetForConstructors = new HashMap<>();
        ArrayList<String> constructorTypes = new ArrayList<>(new HashSet<>(TestModifier.constructorsInTest));
        int constructorsSize = TestModifier.constructorsInTest.size();

        //get params of the class constructors used in the test class
        Map<String, NodeList<Parameter>> constParameters = getConstructorParams(constructorTypes);

        //select only those variable in test class that can be converted to constructor param types
        Map<String, NodeList<VariableDeclarator>> inputsForConstructors = new HashMap<>();
        for(Map.Entry<String, NodeList<Parameter>> entry: constParameters.entrySet()){
            NodeList<VariableDeclarator> varWithoutDuplicates =
                    new NodeList<>(new HashSet<>(typeFilter.getFilteredInputs(entry.getValue(), getVarsForObject(fieldsInTest, entry.getKey()))));
            inputsForConstructors.put(entry.getKey(), varWithoutDuplicates);
        }

        //convert potential input values to constructor param types
        for(Map.Entry<String, NodeList<Parameter>> entry: constParameters.entrySet()){
            String className = ConstructorMapper.targetConstructors.get(entry.getKey()).getNameAsString();
            Map<Integer, List<String>> potentialArgs = typeConverter.getArguments(entry.getValue(), inputsForConstructors.get(entry.getKey()),
                    cu, className);
            argsetForConstructors.put(entry.getKey(), getArgSet(potentialArgs));
        }

        //if constructor parameters contain new inputs of reference type, get arg set for new inputs too
        if(constructorsSize < TestModifier.constructorsInTest.size()){
            getArgSetForConstructors(typeFilter, typeConverter, fieldsInTest);
        }

        return argsetForConstructors;
    }

    private Map<String, NodeList<Parameter>> getConstructorParams(ArrayList<String> constructorTypes){
        Map<String, NodeList<Parameter>> parameters = new HashMap<>();
        HashSet<String> targetClasses = new HashSet<>();
        //for replaced constructor
        for(String objExpr : ConstructorMapper.targetConstructors.keySet()){
            parameters.put(objExpr, ConstructorMapper.targetConstructors.get(objExpr).getParameters());
            targetClasses.add(ConstructorMapper.targetConstructors.get(objExpr).getNameAsString());
        }
        //for new constructor which is required for new input generation
        for(String name: constructorTypes){
            if(!targetClasses.contains(name)){
                String path = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getTargetDir()), TestModifier.getFileNameOfInnerClass(name)+".java");
                if(path != null){
                    CompilationUnit cUnit = SetupTargetApp.getCompilationUnit(new File(path));
                    ConstructorDeclaration constructor = getTargetObjParams(cUnit, name);
                    parameters.put(new ObjectCreationExpr().setType(name).toString(), constructor.getParameters());
                    ConstructorMapper.targetConstructors.put(new ObjectCreationExpr().setType(name).toString(), constructor);
                }
            }
        }
        return parameters;
    }

    private Map<String, List<List<String>>> getArgSetForMethods(InputTypeFilter typeFilter, InputTypeConverter typeConverter,
                                                                NodeList<VariableDeclarator> fieldsInTest){
        Map<String, List<List<String>>> argsetForMethods = new HashMap<>();

        //get params of the target class methods used in the test class
        Map<String, NodeList<Parameter>> methodParameters = new HashMap<>();
        for(Map.Entry<String, ArrayList<MethodCallExpr>> entry: TestModifier.replacedMethods.entrySet()){
            CompilationUnit targetCU = getCU(entry.getKey());
            if(targetCU != null){
                methodParameters.put(entry.getKey(), getTargetMethodParams(targetCU, entry.getKey()));
            }
        }

        //select only those variable in test class that can be converted to method param types
        Map<String, NodeList<VariableDeclarator>> inputsForMethods = new HashMap<>();
        for(Map.Entry<String, NodeList<Parameter>> entry: methodParameters.entrySet()){
            NodeList<VariableDeclarator> varWithoutDuplicates =
                    new NodeList<>(new HashSet<>(typeFilter.getFilteredInputs(entry.getValue(), getVarsForMethod(fieldsInTest, entry.getKey()))));
            inputsForMethods.put(entry.getKey(), varWithoutDuplicates);
        }

        //convert potential input values to method param types
        for(Map.Entry<String, NodeList<Parameter>> entry: methodParameters.entrySet()){
            String methodName = entry.getKey();
            Map<Integer, List<String>> potentialArgs = typeConverter.getArguments(entry.getValue(), inputsForMethods.get(methodName),
                    cu, methodName);
            argsetForMethods.put(methodName, getArgSet(potentialArgs));
        }

        return argsetForMethods;
    }

    private CompilationUnit getCU(String methodName){
        if(MethodMatcher.targetMethodAndClass.containsKey(methodName)){
            String className = MethodMatcher.targetMethodAndClass.get(methodName);
            String path = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getTargetDir()), TestModifier.getFileNameOfInnerClass(className)+".java");
            if(path != null){
                return SetupTargetApp.getCompilationUnit(new File(path));
            }
        }else if(methodName.equals(targetTestMethod)){
            String path = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getTargetDir()), TestModifier.getFileNameOfInnerClass(targetClassName)+".java");
            return SetupTargetApp.getCompilationUnit(new File(path));
        }
        return null;
    }

    //get cartesian product of input values
    private List<List<String>> getArgSet(Map<Integer, List<String>> potentialArg){
        List<List<String>> temp = new ArrayList<>();
        for(int i=0; i<potentialArg.size(); i++)
            temp.add(potentialArg.get(i));
        List<List<String>> arg = Lists.cartesianProduct(temp);
        return filterArgSet(arg);
    }

    //remove arg set that contains same values in each arg position
    private List<List<String>> filterArgSet(List<List<String>> arg){
        List<List<String>> argSet = new ArrayList<>();
        for(List<String> eachSet: arg){
            if(!argSet.contains(eachSet)){
                argSet.add(eachSet);
            }
        }
        return argSet;
    }

    //select an arg set for the object and method from the potential list of args
    private Map<String, List<String>> selectInput(List<List<String>> argSet, Map<String, List<List<String>>> argListMethods,
                                                  Map<String, List<List<String>>> argListConstructors){

        Map<String, List<String>> argsetMethods = new LinkedHashMap<>();
        ArrayList<String> methodNames = new ArrayList<>(argListMethods.keySet());
        if(!methodNames.isEmpty() && !argSet.isEmpty()){
            for(int i=0; i<methodNames.size(); i++){
                argsetMethods.put(methodNames.get(i), argSet.get(i));
            }
        }
        selectInputForMethods(argsetMethods, argListMethods);

        Map<String, List<String>> argsetConstructors = new LinkedHashMap<>();
        ArrayList<String> objExprs = new ArrayList<>(argListConstructors.keySet());
        int index = methodNames.size();
        if(!argSet.isEmpty()){
            for (String expr : objExprs) {
                argsetConstructors.put(expr, argSet.get(index));
                index++;
            }
        }
        selectInputForConstructors(argsetConstructors, argListConstructors);
        new TestModifier().commitChanges(cu, testFile);

        Map<String, List<String>> argMethodConstructor = new LinkedHashMap<>(argsetMethods);
        argMethodConstructor.putAll(argsetConstructors);
        return argMethodConstructor;
    }

    private void selectInputForMethods(Map<String, List<String>> argForMethods, Map<String, List<List<String>>> argListMethods){
        for(Map.Entry<String, List<String>> entry: argForMethods.entrySet()){
            NodeList<MethodCallExpr> mCallExprs = getMethodCallExprs(entry.getKey());
            //if the input value of the same type is used in the existing source method,
            // use the same value in the target method
            Map<String, Integer> multiMethodsSameName = new HashMap<>();
            getMethodNameNumber(multiMethodsSameName, mCallExprs);
            for(MethodCallExpr methodCallExpr: mCallExprs){
                //get arg of the source methods
                ArrayList<Expression> sourceArgList = new ArrayList<>();
                int lineNum = methodCallExpr.getBegin().get().line;
                String key = methodCallExpr.toString()+lineNum;
                if(multiMethodsSameName.get(methodCallExpr.getNameAsString()) == 1){
                    key = methodCallExpr.getNameAsString()+lineNum;
                }
                //TODO: methodCallExpr.toString() vs methodCallExpr.getNameAsString()
                if(!sourceArg.containsKey(key)){
                    sourceArgList.addAll(methodCallExpr.getArguments());
                    sourceArg.put(key, sourceArgList);
                }else{
                    sourceArgList.addAll(sourceArg.get(key));
                }
                //get the parameter index of the matching source arg in the target method
                Map<String, Integer> argPosition = new HashMap<>();
                List<List<String>> argList = argListMethods.get(methodCallExpr.getNameAsString());
                for(Expression argValue: sourceArgList){
                    for(List<String> potentialArg: argList){
                        if(potentialArg.contains(argValue.toString())){
                            argPosition.put(argValue.toString(), potentialArg.indexOf(argValue.toString()));
                        }
                    }
                }

                NodeList<Expression> argMethodExpr = new NodeList<>();
                for(String value: entry.getValue()){
                    argMethodExpr.add(new NameExpr().setName(value));
                }
                for(Map.Entry<String, Integer> entry1: argPosition.entrySet()){
                    argMethodExpr.replace(argMethodExpr.get(entry1.getValue()), new NameExpr().setName(entry1.getKey()));
                }

                //if the source method call has literals or null as arguments, keep them in the target method call
                MethodCallExpr sourceMethodCall = getSourceMethodCall(methodCallExpr);
                if(matchMethodParamTypes(sourceMethodCall, methodCallExpr.getNameAsString())){
                    for(int i=0; i<sourceArgList.size(); i++){
                        if((isLiteralNullOrStringLiteral(sourceArgList.get(i)) || sourceArgList.get(i).isMethodCallExpr()
                                || sourceArgList.get(i).isObjectCreationExpr() || sourceArgList.get(i).isArrayCreationExpr())){
                            if(i < argMethodExpr.size()){
                                argMethodExpr.replace(argMethodExpr.get(i), sourceArgList.get(i));
                            }else if(varArgInTarget){
                                argMethodExpr.add(sourceArgList.get(i));
                            }
                        }
                    }
                }
                methodCallExpr.setArguments(argMethodExpr);
            }
        }
    }

    private MethodCallExpr getSourceMethodCall(MethodCallExpr targetMethodCallExpr){
        ArrayList<MethodCallExpr> sourceMethodCalls = TestModifier.replacedMethods.get(targetMethodCallExpr.getNameAsString());
        if(sourceMethodCalls == null){
            return null;
        }else if(sourceMethodCalls.size() == 1){
            return sourceMethodCalls.get(0);
        }else{
            ArrayList<String> targetParamTypes = new ArrayList<>();
            for(Parameter parameter: getTargetMethodParams(getTargetCU(), targetMethodCallExpr.getNameAsString())){
                targetParamTypes.add(parameter.getTypeAsString());
            }

            ArrayList<MethodCallExpr> potentialSourceMethods = new ArrayList<>();
            for(MethodCallExpr source: sourceMethodCalls){
                if(MethodCallResolver.sourceParamTypes.containsKey(source)){
                    ArrayList<String> sourceParamTypes = MethodCallResolver.sourceParamTypes.get(source);
                    if(matchParamTypes(sourceParamTypes, targetParamTypes)){
                        potentialSourceMethods.add(source);
                    }
                }
            }

            if(varArgInTarget && !potentialSourceMethods.isEmpty()){
                return potentialSourceMethods.get(0);
            }else {
                for(MethodCallExpr expr: potentialSourceMethods){
                    if(expr.getArguments().size() == targetParamTypes.size()){
                        return expr;
                    }
                }
            }
        }
        return null;
    }

    private boolean hasNoVarArgInTargetMethod(){
        for(Parameter parameter: getTargetMethodParams(getTargetCU(), new CodeSearchResults().getTargetTestMethod())){
            if(parameter.isVarArgs()) {
                varArgInTarget = true;
                return false;
            }
        }
        return true;
    }

    private boolean isLiteralNullOrStringLiteral(Expression arg){
        if(arg.isLiteralExpr() || arg.isStringLiteralExpr()){
            return true;
        }else if(arg.isFieldAccessExpr() && isPrimitiveConstant(arg)){
            return true;
        }else if(arg.isUnaryExpr() && isDigit(arg)){
            return true;
        }else if(checkConstant(arg)){
            return true;
        }else if(arg.isCastExpr() && arg.asCastExpr().getExpression().isLiteralExpr()){
            return true;
        }else if(arg.isBinaryExpr() && isLiteralNullOrStringLiteral(arg.asBinaryExpr().getLeft())
                && isLiteralNullOrStringLiteral(arg.asBinaryExpr().getRight())){
            return true;
        }

        return false;
    }

    private boolean checkConstant(Expression argExpr){
        ArrayList<String> potentialConstants = getAllLastNameImports();
        String argName = argExpr.toString();
        String constant = argName.substring(argName.lastIndexOf(".")+1);
        if(argName.contains(".") && StringUtils.isAllUpperCase(constant)){
            return true;
        }else return StringUtils.isAllUpperCase(argName) && potentialConstants.contains(argName);
    }

    private ArrayList<String> getAllLastNameImports(){
        ArrayList<String> potentialConstants = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(ImportDeclaration node, Object arg){
                super.visit(node, arg);
                String importName = node.getNameAsString();
                if(importName.contains(".")){
                    potentialConstants.add(importName.substring(importName.lastIndexOf(".")+1));
                }else{
                    potentialConstants.add(importName);
                }
            }
        }, null);
        return potentialConstants;
    }

    private boolean isDigit(Expression arg){
        String srgString = arg.toString();
        for(int i=0; i<srgString.length(); i++){
            if(Character.isLetter(srgString.charAt(i))){
                return false;
            }
        }
        return true;
    }

    private boolean isPrimitiveConstant(Expression arg){
        //TODO: convert this into pattern matching
        ArrayList<String> constantClass = new ArrayList<>(Arrays.asList("Byte", "Short", "Integer", "Long", "Float", "Double", "Thread"));
        ArrayList<String> constantValues = new ArrayList<>(
                Arrays.asList("BYTES", "MAX_VALUE", "MIN_VALUE", "SIZE", "MAX_EXPONENT", "MIN_EXPONENT", "NaN", "NEGATIVE_INFINITY",
                        "POSITIVE_INFINITY", "MIN_NORMAL", "MIN_PRIORITY", "MAX_PRIORITY"));

        String receiver = arg.asFieldAccessExpr().getScope().toString();
        String field = arg.asFieldAccessExpr().getNameAsString();

        if(constantClass.contains(receiver) && constantValues.contains(field)){
            return true;
        }

        return false;
    }

    private void selectInputForConstructors(Map<String, List<String>> argForConstructors, Map<String, List<List<String>>> argListConstructors){
        for(Map.Entry<String, List<String>> entry: argForConstructors.entrySet()){
            Map<String, String> replacedExprs = new HashMap<>();
            for(Map.Entry<Statement, ArrayList<ObjectCreationExpr>> obExprs : getObjectCreationExprs(entry.getKey()).entrySet()){
                for(ObjectCreationExpr obExpr : obExprs.getValue()){
                    ArrayList<Expression> sourceArgList = getSourceArgs(obExpr);
                    List<List<String>> argList = getArgList(argListConstructors, replacedExprs, obExpr);
                    Map<String, Integer> argPosition = getArgPosition(sourceArgList, argList);
                    NodeList<Expression> argObjExpr = getExprFromValue(entry.getValue(), argPosition);

                    //if the source constructor has literals or null as arguments, keep them in the target constructor
                    if(matchConstructorParamTypes(obExpr)){
                        keepSourceArgs(sourceArgList, argObjExpr);
                    }

                    ObjectCreationExpr clonedExpr = obExpr.clone();
                    obExpr.setArguments(argObjExpr);
                    replacedExprs.put(obExpr.toString(), clonedExpr.toString());

                    if(argObjExpr.isNonEmpty() && obExprs.getKey() != null){
                        List<AssignExpr> initializedAfter = argInitialized(obExprs.getKey(), entry.getValue());
                        if(!initializedAfter.isEmpty())
                            moveObject(obExprs.getKey(), initializedAfter.get(0));
                    }
                }

            }
        }
    }

    private NodeList<Expression> getExprFromValue(List<String> values, Map<String, Integer> argPosition){
        NodeList<Expression> argObjExpr = new NodeList<>();
        for(String value: values){
            argObjExpr.add(new NameExpr().setName(value));
        }
        for(Map.Entry<String, Integer> entry1: argPosition.entrySet()){
            argObjExpr.replace(argObjExpr.get(entry1.getValue()), new NameExpr().setName(entry1.getKey()));
        }
        return argObjExpr;
    }

    private void keepSourceArgs(ArrayList<Expression> sourceArgList, NodeList<Expression> argObjExpr){
        for(int i=0; i<sourceArgList.size(); i++){
            if((isLiteralNullOrStringLiteral(sourceArgList.get(i)) || sourceArgList.get(i).isMethodCallExpr()
                    || sourceArgList.get(i).isObjectCreationExpr() || sourceArgList.get(i).isArrayCreationExpr()) && i < argObjExpr.size()){
                argObjExpr.replace(argObjExpr.get(i), sourceArgList.get(i));
            }
        }
    }

    //get potential arguments for the constructor
    private List<List<String>> getArgList(Map<String, List<List<String>>> argListConstructors, Map<String, String> replacedExprs, ObjectCreationExpr obExpr){
        List<List<String>> argList = new ArrayList<>();
        if(argListConstructors.containsKey(obExpr.toString()) && replacedExprs.containsKey(obExpr.toString())){
            argList.addAll(argListConstructors.get(replacedExprs.get(obExpr.toString())));
        }else if(argListConstructors.containsKey(obExpr.toString())){
            argList.addAll(argListConstructors.get(obExpr.toString()));
        }
        return argList;
    }

    //get the parameter index of the matching source arg in the target constructor
    private Map<String, Integer> getArgPosition(ArrayList<Expression> sourceArgList, List<List<String>> argList){
        Map<String, Integer> argPosition = new HashMap<>();
        for(Expression argValue: sourceArgList){
            for(List<String> potentialArg: argList){
                if(potentialArg.contains(argValue.toString())){
                    argPosition.put(argValue.toString(), potentialArg.indexOf(argValue.toString()));
                }
            }
        }
        return argPosition;
    }

    //get arg of the source constructor
    private ArrayList<Expression> getSourceArgs(ObjectCreationExpr obExpr){
        ArrayList<Expression> sourceArgList = new ArrayList<>();
        if(!sourceArgConstructor.containsKey(obExpr)){
            sourceArgList.addAll(obExpr.getArguments());
            sourceArgConstructor.put(obExpr, sourceArgList);
        }else{
            sourceArgList.addAll(sourceArgConstructor.get(obExpr));
        }
        return sourceArgList;
    }

    //move object after the variable used in object arg has been initialized
    private void moveObject(Statement remove, AssignExpr initializedVar){
        Statement after = StaticJavaParser.parseStatement(initializedVar.toString()+";");
        cu.accept(new VoidVisitorAdapter<>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                if(node.getBody().isPresent()){
                    node.getBody().get().getStatements().remove(remove);
                    if(node.getBody().get().getStatements().contains(after))
                        node.getBody().get().getStatements().addAfter(remove, after);
                }
            }
        }, null);

    }

    //check arg initialized before used in object creation expression
    private List<AssignExpr> argInitialized(Statement objAssignExpr, List<String> args){
        List<AssignExpr> initializedVar = new ArrayList<>();
        int line = objAssignExpr.getBegin().get().line;

        cu.accept(new VoidVisitorAdapter<>() {
            @Override
            public void visit(AssignExpr assignExpr, Object arg){
                super.visit(assignExpr, arg);
                String target = assignExpr.getTarget().toString();
                int lineNum = assignExpr.getBegin().get().line;
                //do nothing if the constructor does not use the passed input (here target)
                ArrayList<String> arguments = new ArrayList<>();
                if(assignExpr.getValue().isObjectCreationExpr()){
                    assignExpr.getValue().asObjectCreationExpr().getArguments().forEach(argument -> arguments.add(argument.toString()));
                }
                if(args.get(0).contains(target) && line<lineNum && arguments.contains(target)){
                    initializedVar.add(assignExpr);
                }
            }
        }, null);
        return initializedVar;
    }

    //get object creation expression and the statement from the test class
    private Map<Statement, ArrayList<ObjectCreationExpr>> getObjectCreationExprs(String objExpr){
        Map<Statement, ArrayList<ObjectCreationExpr>> objectCreationExprs = new HashMap<>();
        cu.accept(new VoidVisitorAdapter<>() {
            @Override
            public void visit(AssignExpr node, Object arg){
                super.visit(node, arg);
                Expression expression = node.getValue();
                if(expression.isObjectCreationExpr()){
                    ObjectCreationExpr objectExpr = expression.asObjectCreationExpr();
                    if(objectExpr.toString().equals(objExpr)){
                        Statement statement = getStatement(node.toString());
                        if(objectCreationExprs.containsKey(statement)){
                            objectCreationExprs.get(statement).add(objectExpr);
                        }else{
                            objectCreationExprs.put(statement, new ArrayList<>(Arrays.asList(objectExpr)));
                        }
                    }
                }
            }
            @Override
            public void visit(VariableDeclarationExpr node, Object arg){
                super.visit(node, arg);
                if(node.getElementType().asString().equals(ConstructorMapper.targetConstructors.get(objExpr).getNameAsString())){
                    Optional<Expression> expression = node.getVariable(0).getInitializer();
                    if(expression.isPresent() && expression.get().isObjectCreationExpr() && expression.get().asObjectCreationExpr().toString().equals(objExpr)){
                        Statement statement = getStatement(node.toString());
                        if(objectCreationExprs.containsKey(statement)){
                            objectCreationExprs.get(statement).add(expression.get().asObjectCreationExpr());
                        }else{
                            objectCreationExprs.put(statement, new ArrayList<>(Arrays.asList(expression.get().asObjectCreationExpr())));
                        }
                    }
                }
            }

            @Override
            public void visit(FieldDeclaration node, Object arg){
                super.visit(node, arg);
                if(node.getElementType().asString().equals(ConstructorMapper.targetConstructors.get(objExpr).getNameAsString())){
                    Optional<Expression> expression = node.getVariable(0).getInitializer();
                    if(expression.isPresent() && expression.get().isObjectCreationExpr() && expression.get().asObjectCreationExpr().toString().equals(objExpr)){
                        Statement statement = getStatement(node.toString());
                        if(objectCreationExprs.containsKey(statement)){
                            objectCreationExprs.get(statement).add(expression.get().asObjectCreationExpr());
                        }else{
                            objectCreationExprs.put(statement, new ArrayList<>(Arrays.asList(expression.get().asObjectCreationExpr())));
                        }
                    }
                }
            }

            @Override
            public void visit(ObjectCreationExpr expr, Object arg){
                super.visit(expr, arg);
                if(expr.toString().equals(objExpr)){
                    if(objectCreationExprs.containsKey(null)){
                        objectCreationExprs.get(null).add(expr);
                    }else{
                        objectCreationExprs.put(null, new ArrayList<>(Arrays.asList(expr)));
                    }
                }
            }

        }, null);
        return objectCreationExprs;
    }

    //convert expression to statement
    private Statement getStatement(String expression){
        List<Statement> statement = new ArrayList<>();
        String nodeSting = expression+";";
        cu.accept(new VoidVisitorAdapter<>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                if(node.getBody().isPresent() && node.getBody().get().getStatements().toString().contains(nodeSting)){
                    node.getBody().get().getStatements().forEach(stmt -> {
                        if(stmt.toString().contains(nodeSting)){
                            stmt.removeComment();
                        }
                        if(stmt.toString().equals(nodeSting)){
                            statement.add(stmt);
                        }else if(stmt.isTryStmt() && stmt.toString().contains(nodeSting)){
                            statement.add(stmt);
                        }
                    });
                }
            }
        }, null);
        if(statement.isEmpty()){
            return null;
        }
        return statement.get(0);
    }

    //get target method calls from the test class
    private NodeList<MethodCallExpr> getMethodCallExprs(String methodName){
        NodeList<MethodCallExpr> mCallExprs = new NodeList<>();
        cu.accept(new VoidVisitorAdapter<>() {
            @Override
            public void visit(MethodCallExpr node, Object arg){
                super.visit(node, arg);
                if(node.getNameAsString().equals(methodName))
                    mCallExprs.add(node);
            }
        }, null);
        return mCallExprs;
    }

    //get potential (class+local) variables that can be used as arg in the target object
    private NodeList<VariableDeclarator> getVarsForObject(NodeList<VariableDeclarator> fieldsInTest, String objExpr){
        NodeList<VariableDeclarator> variables = new NodeList<>(fieldsInTest);
        List<MethodDeclaration> methodDeclaration = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                node.accept(new VoidVisitorAdapter<>() {
                    @Override
                    public void visit(ObjectCreationExpr creationExpr, Object arg){
                        super.visit(creationExpr, arg);
                        if(creationExpr.toString().equals(objExpr))
                            methodDeclaration.add(node);
                    }
                }, null);
            }
        }, null);
        variables.addAll(getLocalVars(methodDeclaration, objExpr));
        return variables;
    }

    //get potential (class+local) variables that can be used as arg in the target method
    private NodeList<VariableDeclarator> getVarsForMethod(NodeList<VariableDeclarator> fieldsInTest, String methodName){
        NodeList<VariableDeclarator> variables = new NodeList<>(fieldsInTest);
        List<MethodDeclaration> methodDeclaration = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<>() {
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
        variables.addAll(getLocalVars(methodDeclaration, null));
        return variables;
    }

    //get local variables declared in a method
    private NodeList<VariableDeclarator> getLocalVars(List<MethodDeclaration> methodDeclaration, String callExpr){
        NodeList<VariableDeclarator> localVars = new NodeList<>();
        for (MethodDeclaration mdNode: methodDeclaration){
            mdNode.accept(new VoidVisitorAdapter<Object>() {
                @Override
                public void visit(VariableDeclarator node, Object arg){
                    super.visit(node, arg);
                    if(callExpr == null){
                        localVars.add(node);
                    }else if(declarationBeforeUse(mdNode, node, callExpr)){
                        localVars.add(node);
                    }
                }
            }, null);
        }
        return localVars;
    }

    private boolean declarationBeforeUse(MethodDeclaration md, VariableDeclarator vd, String callExpr){
        String mdString = md.toString();
        String tempString  = mdString.substring(0, mdString.indexOf(callExpr));
        if(tempString.contains(vd.toString())){
            return true;
        }
        return false;
    }

    //get method parameters
    private NodeList<Parameter> getTargetMethodParams(CompilationUnit targetCU, String methodName){
        int argsize = 0;
        NodeList<MethodCallExpr> callExprs = getMethodCallExprs(methodName);
        if(callExprs.isNonEmpty()){
            argsize = callExprs.get(0).getArguments().size();
        }

        NodeList<MethodDeclaration> targetMethods = new NodeList<>();
        NodeList<Parameter> methodParams = new NodeList<>();
        int finalArgsize = argsize;
        final boolean[] paramSelected = {false};
        targetCU.accept(new VoidVisitorAdapter<>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                if(node.getNameAsString().equals(methodName)){
                    targetMethods.add(node);
                    //if condition for overloaded methods
                    if(node.getParameters().size() == finalArgsize && methodParams.isEmpty()){
                        paramSelected[0] = true;
                        methodParams.addAll(node.getParameters());
                    }
                }
            }
        }, null);
        if(!paramSelected[0] && targetMethods.isNonEmpty()){
            methodParams.addAll(targetMethods.get(0).getParameters());
        }
        if(methodName.equals(targetTestMethod)){
            targetParamSize = methodParams.size();
        }
        return methodParams;
    }

    //select a constructor
    private ConstructorDeclaration getTargetObjParams(CompilationUnit targetCU, String className){
        //for inner class
        if(className.contains(".")){
            className = className.substring(className.lastIndexOf(".")+1);
        }
        List<ConstructorDeclaration> cdNode = new ArrayList<>(targetCU.findAll(ConstructorDeclaration.class));
        if(cdNode.size() >= 1 && cdNode.get(0).getNameAsString().equals(className)){
            for(ConstructorDeclaration cd : cdNode){
                if(cd.getParameters().isEmpty()){
                    return cd;
                }
            }
            return cdNode.get(0);
        }
        //return default constructor
        return new ConstructorDeclaration().setName(className);
    }

    //get all class variables declared in the test class
    private NodeList<VariableDeclarator> getClassVarsFromTest(){
        NodeList<VariableDeclarator> variableDeclarators = new NodeList<>();
        cu.accept(new VoidVisitorAdapter<>() {
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

    private boolean matchConstructorParamTypes(ObjectCreationExpr creationExpr){
        ConstructorDeclaration source = ConstructorMapper.replacedSourceConstructors.get(creationExpr.toString());
        ConstructorDeclaration target = ConstructorMapper.targetConstructors.get(creationExpr.toString());
        if(source == null || target == null){
            return false;
        }
        return matchParamTypes(new ConstructorMapper().getParamTypes(source), new ConstructorMapper().getParamTypes(target));
    }

    private boolean matchMethodParamTypes(MethodCallExpr sourceMethodCall, String targetMethodName) {
        ArrayList<String> sourceParamTypes = new ArrayList<>();
        if(sourceMethodCall != null){
            if(MethodCallResolver.sourceParamTypes.containsKey(sourceMethodCall)){
                sourceParamTypes.addAll(MethodCallResolver.sourceParamTypes.get(sourceMethodCall));
            }else{
                sourceParamTypes.addAll(getParams(getSourceCU(), sourceMethodCall.getNameAsString(), sourceMethodCall.getArguments().size()));
            }
        }

        ArrayList<String> targetParamTypes = new ArrayList<>();
        getTargetMethodParams(getTargetCU(), targetMethodName).forEach( parameter -> targetParamTypes.add(parameter.getTypeAsString()));

        return matchParamTypes(sourceParamTypes, targetParamTypes);
    }

    private CompilationUnit getSourceCU(){
        String sourcePath = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getSourceDir()), TestModifier.getFileNameOfInnerClass(sourceClassName)+".java");
        return SetupTargetApp.getCompilationUnit(new File(sourcePath));
    }

    private CompilationUnit getTargetCU(){
        String path = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getTargetDir()), TestModifier.getFileNameOfInnerClass(targetClassName)+".java");
        return SetupTargetApp.getCompilationUnit(new File(path));
    }

    private boolean matchParamTypes(ArrayList<String> sourceParamTypes, ArrayList<String> targetParamTypes){
        if(!sourceParamTypes.isEmpty() && targetParamTypes.isEmpty()){
            return false;
        }
        if(sourceParamTypes.size() == targetParamTypes.size() || sourceParamTypes.size() > targetParamTypes.size()){
            for(int i=0; i<targetParamTypes.size(); i++){
                String sourceType = sourceParamTypes.get(i);
                String targetType = targetParamTypes.get(i);
                if(sourceType.endsWith("[]") && targetType.endsWith("[]")){
                    sourceType = sourceType.substring(0, sourceType.length()-2);
                    targetType = targetType.substring(0, targetType.length()-2);
                }
                boolean convertible = new InputTypeFilter().toTargetTypes(sourceType, new ArrayList<>(Collections.singletonList(targetType)));
                if(!sourceType.equals(targetType) && !isSubClass(targetType) && !convertible){
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private ArrayList<String> getParams(CompilationUnit cu, String methodName, int paramSize){
        ArrayList<String> parameterTypes = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                //empty check for overloaded methods. pick only one method declaration
                if(parameterTypes.isEmpty() && node.getNameAsString().equals(methodName) && node.getParameters().size() == paramSize){
                    for(Parameter parameter: node.getParameters()){
                        parameterTypes.add(parameter.getTypeAsString());
                    }
                }
            }
        }, null);
        return parameterTypes;
    }

    private void getMethodNameNumber(Map<String, Integer> nameNumber, NodeList<MethodCallExpr> mCallExprs){
        for(MethodCallExpr callExpr: mCallExprs){
            String name = callExpr.getNameAsString();
            if(nameNumber.containsKey(name)){
                int value = nameNumber.get(name)+1;
                nameNumber.replace(name, value);
            }else{
                nameNumber.put(name, 1);
            }
        }
    }

    private boolean isSubClass(String targetType){
        //TODO: need to find solution like SuperClass.isAssignableFrom(SubClass)
        //check spring ClassUtils.forName
        boolean subClass = false;
        if(targetType.equals("Object") || targetType.equals("T")){
            subClass = true;
        }
        return subClass;
    }

    private long getTimeOut() {
        String timeout = null;
        Properties prop = new Properties();
        InputStream input;
        try {
            input = new FileInputStream("config.properties");
            prop.load(input);
            timeout = prop.getProperty("executionTimeOut");
            input.close();
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        if(timeout == null) {
            System.out.println("Set execution timeout in config.properties file.");
            System.exit(1);
        }
        return Long.parseLong(timeout);
    }
}
