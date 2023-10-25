package stam.testmigration.main;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.math3.random.RandomDataGenerator;
import org.apache.commons.text.similarity.LevenshteinDistance;
import stam.testmigration.search.CodeSearchResults;
import stam.testmigration.setup.ConfigurationRetriever;
import stam.testmigration.setup.SetupTargetApp;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class InputGenerator {

    CodeSearchResults searchResults = new CodeSearchResults();
    SetupTargetApp setupTargetApp = new SetupTargetApp();
    CompilationUnit cu;
    String methodOrConstructorName;
    private final ArrayList<Parameter> inputList;
    private final SetterMethodFinder setterMethodFinder = new SetterMethodFinder();
    private static final ArrayList<VariableDeclarator> addedVars = new ArrayList<>();

    InputGenerator(CompilationUnit cu, String methodOrConstructorName, ArrayList<Parameter> inputList){
        this.cu = cu;
        this.methodOrConstructorName = methodOrConstructorName;
        this.inputList = inputList;
    }

    void generateInput(){
        ArrayList<Type> referenceTypeInTarget = filterInputList(inputList);
        if(!referenceTypeInTarget.isEmpty()){
            generateInputTargetType(referenceTypeInTarget);
        }

        ArrayList<Parameter> filteredInputs = new ArrayList<>();
        for(Parameter parameter: inputList){
            if(!referenceTypeInTarget.contains(parameter.getType())){
                filteredInputs.add(parameter);
            }
        }

        for(Parameter param: filteredInputs){
            if(param.getType().isReferenceType()){
                generateInputReference(param);
            }else if(param.getType().isPrimitiveType()){
                generateInputPrimitive(param);
            }
        }
    }

    private void generateInputTargetType(ArrayList<Type> referenceTypeInTarget){
        ArrayList<Type> replacedClass = new ArrayList<>();
        //if the reference type is a parameter of one of the semantically similar methods
        if(MethodMatcher.similarMethods.containsValue(methodOrConstructorName)){
            ArrayList<Type> paramsInSource = getSourceMethodParams(methodOrConstructorName);
            if(paramsInSource.size() == 1 && referenceTypeInTarget.size() == 1){
                replaceType(paramsInSource.get(0), referenceTypeInTarget.get(0));
                replacedClass.add(referenceTypeInTarget.get(0));
                addImportReferenceType(referenceTypeInTarget.get(0));
                setterMethodFinder.addSetterMethods(cu, referenceTypeInTarget.get(0));
            }else if(referenceTypeInTarget.size() >= 1){
                //TODO: need to test on actual cases
                Map<Type, Type> similarClasses = getSimilarClasses(paramsInSource, referenceTypeInTarget);
                for(Map.Entry<Type, Type> entry: similarClasses.entrySet()){
                    replaceType(entry.getKey(), entry.getValue());
                    replacedClass.add(entry.getValue());
                    addImportReferenceType(entry.getValue());
                    setterMethodFinder.addSetterMethods(cu, entry.getValue());
                }
            }
        }

        //if semantic similar class is not instantiated in test, instantiate the class
        for(Type refType: referenceTypeInTarget){
            if(!replacedClass.contains(refType)){
                for(Parameter parameter: inputList){
                    String paramType = parameter.getTypeAsString();
                    String paramName = parameter.getNameAsString();
                    if(paramType.equals(refType.asString())){
                        ArrayList<Statement> mCallStmts = new ArrayList<>();
                        getMethodCallExprs(mCallStmts);

                        VariableDeclarator vd = new VariableDeclarator().setType(paramType).setName(paramName);
                        vd.setInitializer(new ObjectCreationExpr().setType(paramType));
                        VariableDeclarationExpr variableDeclarationExpr = new VariableDeclarationExpr().addVariable(vd);
                        addClassInstantiation(mCallStmts, variableDeclarationExpr);
                        addImportReferenceType(parameter.getType());
                        setterMethodFinder.addSetterMethods(cu, parameter.getType());
                        //Need to pass parameters later
                        if(!TestCodeTransformer.constructorsInTest.contains(paramType)){
                            TestCodeTransformer.constructorsInTest.add(paramType);
                        }
                    }
                }
            }
        }

    }

    private void generateInputReference(Parameter param){
        String paramType = param.getTypeAsString();
        String paramName = checkNameExists(param.getNameAsString());

       if(paramType.equals("String")){
           if(!getFieldNames().contains(paramName)){
               VariableDeclarator vd = new VariableDeclarator().setType(paramType).setName(paramName);
               vd.setInitializer("\""+ RandomStringUtils.random(4, true, false)+paramName+"\"");
               cu.getType(0).getMembers().add(0, new FieldDeclaration().addVariable(vd).setPrivate(true));
           }
        }
        //if the reference type is a library class, and not a generic type
        else if(!paramType.equals("T") && !paramType.equals("T[]")){
            VariableDeclarator vd = new VariableDeclarator().setType(paramType).setName(paramName);
            //TODO: initialize the field with appropriate input value
           if(!addedVars.contains(vd)){
               addedVars.add(vd);
               cu.getType(0).getMembers().add(0, new FieldDeclaration().addVariable(vd).setPrivate(true));
           }


            String importType = paramType;
            if(paramType.contains("<"))
                importType = paramType.substring(0, paramType.indexOf('<'));
            addImport(cu, importType);
        }
    }

    //add import statement for new input types
    private void addImport(CompilationUnit testFileCU, String importType){
        String targetFileName = searchResults.getTargetFileName();
        String path = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getTargetDir()), targetFileName);
        CompilationUnit targetFileCU = SetupTargetApp.getCompilationUnit(new File(path));
        NodeList<ImportDeclaration> imports = targetFileCU.getImports();
        for(ImportDeclaration id : imports){
            if(id.getNameAsString().contains(importType))
                testFileCU.addImport(id);
        }
    }

    private void addImportReferenceType(Type classType){
        String path = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getTargetDir()), classType+".java");
        if(path != null){
            path = path.replace("\\", ".");
            if(path.contains("src.main.java.")){
                path = path.substring(path.lastIndexOf("src.main.java.")+"src.main.java.".length(), path.lastIndexOf("."));
            }else if(path.contains("src.")){
                path = path.substring(path.lastIndexOf("src.")+"src.".length(), path.lastIndexOf("."));
            }
            cu.addImport(path);
        }
    }

    private void generateInputPrimitive(Parameter parameter){
        String paramType = parameter.getTypeAsString();
        String paramName = checkNameExists(parameter.getNameAsString());

        if(!getFieldNames().contains(paramName)){
            VariableDeclarator vd = new VariableDeclarator().setType(paramType).setName(paramName);
            switch (paramType) {
                case "long" -> {
                    long generatedLong = new RandomDataGenerator().nextLong(1L, 100000000000L);
                    vd.setInitializer(generatedLong+"L");
                }
                case "int" -> {
                    int generatedInt = new RandomDataGenerator().nextInt(1, 10);
                    vd.setInitializer(String.valueOf(generatedInt));
                }
                case "float" -> {
                    float generatedFloat = new RandomDataGenerator().getRandomGenerator().nextFloat();
                    vd.setInitializer(String.valueOf(generatedFloat));
                }
                case "double" -> {
                    double generatedDouble = new RandomDataGenerator().getRandomGenerator().nextDouble();
                    vd.setInitializer(String.valueOf(generatedDouble));
                }
            }
            cu.getType(0).getMembers().add(0, new FieldDeclaration().addVariable(vd).setPrivate(true));
        }
    }

    private ArrayList<Type> filterInputList(ArrayList<Parameter> inputList){
        ArrayList<Type> input = new ArrayList<>();
        for(Parameter parameter: inputList){
            if(parameter.getType().isReferenceType()){
                String path = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getTargetDir()), parameter.getTypeAsString()+".java");
                if(path != null && path.contains("src")){
                    CompilationUnit unit = SetupTargetApp.getCompilationUnit(new File(path));
                    if(unit.getType(0).isClassOrInterfaceDeclaration() && !unit.getType(0).asClassOrInterfaceDeclaration().isAbstract()
                            && !unit.getType(0).asClassOrInterfaceDeclaration().isInterface()){
                        input.add(parameter.getType());
                        //TODO: if abstract or interface, get one of the concrete class
                    }
                }
            }
        }
        return input;
    }

    private ArrayList<Type> getSourceMethodParams(String targetMethod){
        ArrayList<Type> params = new ArrayList<>();
        for(Map.Entry<MethodDeclaration, MethodDeclaration> entry: MethodMatcher.similarMethodDecl.entrySet()){
            if(entry.getValue().getNameAsString().equals(targetMethod)){
                MethodDeclaration sourceMethod = entry.getKey();
                for(Parameter parameter: sourceMethod.getParameters()){
                    if(parameter.getType().isReferenceType() && sourceIsReferenceType(parameter.getTypeAsString())){
                        params.add(parameter.getType());
                    }
                }
            }
        }
        return params;
    }

    private boolean sourceIsReferenceType(String paramType){
        boolean referenceType = false;
        String path = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getSourceDir()), paramType+".java");
        if(path != null){
            referenceType = true;
        }
        return referenceType;
    }

    private void replaceType(Type sourceType, Type targetType){
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(FieldDeclaration node, Object arg){
                super.visit(node, arg);
                if(node.getElementType().asString().equals(sourceType.asString())){
                    node.setAllTypes(targetType);
                }
            }
            @Override
            public void visit(VariableDeclarationExpr node, Object arg){
                super.visit(node, arg);
                if(node.getElementType().asString().equals(sourceType.asString())){
                    node.setAllTypes(targetType);
                }
            }
            @Override
            public void visit(ObjectCreationExpr node, Object arg){
                super.visit(node, arg);
                if(node.getTypeAsString().equals(sourceType.asString())){
                    node.setType(targetType.asString());
                }
            }
        }, null);
    }

    private Map<Type, Type> getSimilarClasses(ArrayList<Type> sourceParams, ArrayList<Type> targetParams){
        Map<Type, Type> similarClasses = new HashMap<>();
        LevenshteinDistance levenshteinDistance = new LevenshteinDistance();
        for(Type targetParamType: targetParams){
            double score = 0;
            Type slectedSourceType = null;
            for(Type sourceParamType: sourceParams){
                double newScore = calculateLVSim(levenshteinDistance, sourceParamType.asString(), targetParamType.asString());
                if(newScore>score){
                    score = newScore;
                    slectedSourceType = sourceParamType;
                }
            }
            if(score> ConfigurationRetriever.thresholdValue && slectedSourceType != null){
                similarClasses.put(slectedSourceType, targetParamType);
            }
        }
        return similarClasses;
    }

    private double calculateLVSim(LevenshteinDistance levenshteinDistance, String sourceParam, String targetParam){
        MethodMatcher methodMatcher = new MethodMatcher();
        ArrayList<String> source, target;
        if(!sourceParam.equals(targetParam)){
            source = methodMatcher.splitCamelCase(sourceParam);
            target = methodMatcher.splitCamelCase(targetParam);
            methodMatcher.splitWordFurther(source, target);
            methodMatcher.removeDuplicates(source);
            methodMatcher.removeDuplicates(target);
            methodMatcher.removeMatchingWords(source, target);
        }else{
            source = new ArrayList<>();
            source.add(sourceParam);
            target = new ArrayList<>();
            target.add(targetParam);
        }

        String sourceName = methodMatcher.buildName(source);
        String targetName = methodMatcher.buildName(target);
        double maxLength = (sourceParam.length() == targetParam.length() || sourceParam.length()>targetParam.length())?
                sourceParam.length(): targetParam.length();

        return 1 - ((double)levenshteinDistance.apply(sourceName, targetName)/maxLength);
    }

    private ArrayList<String> getFieldNames(){
        ArrayList<String> fieldNames = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(FieldDeclaration node, Object arg){
                super.visit(node, arg);
                for(VariableDeclarator vd: node.getVariables()){
                    fieldNames.add(vd.getNameAsString());
                }
            }
        }, null);
        return fieldNames;
    }

    private void getMethodCallExprs(ArrayList<Statement> mCallStmts){
        char[] firstCharName = methodOrConstructorName.toCharArray();
        if(Character.isLowerCase(firstCharName[0])){
            cu.accept(new VoidVisitorAdapter<Object>() {
                @Override
                public void visit(MethodCallExpr node, Object arg){
                    if(node.getNameAsString().equals(methodOrConstructorName)){
                        mCallStmts.add(StaticJavaParser.parseStatement(node.toString()+";"));
                    }
                }
            }, null);
        }
    }

    private void addClassInstantiation(ArrayList<Statement> mCallStmts, VariableDeclarationExpr variableDeclarator){
        Statement vdStatement = StaticJavaParser.parseStatement(variableDeclarator.toString()+";");
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                for(Statement statement: mCallStmts){
                    if(node.getBody().isPresent() && node.getBody().get().getStatements().contains(statement)){
                        node.getBody().get().getStatements().addBefore(vdStatement, statement);
                    }
                }
            }
        }, null);
    }

    private String checkNameExists(String name){
        ArrayList<FieldDeclaration> fieldsInTest = new ArrayList<>(cu.getType(0).getFields());
        for(FieldDeclaration field: fieldsInTest) {
            String fieldName = field.getVariable(0).getNameAsString();
            if(fieldName.equals(name)) return name+RandomStringUtils.random(3, true, false);
        }
        return name;
    }

}
