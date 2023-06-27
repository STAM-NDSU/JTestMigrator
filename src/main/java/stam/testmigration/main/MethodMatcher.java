package stam.testmigration.main;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.utils.SymbolSolverCollectionStrategy;
import com.github.javaparser.utils.ProjectRoot;
import com.github.javaparser.utils.SourceRoot;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.deeplearning4j.models.word2vec.Word2Vec;
import stam.testmigration.search.CodeSearchResults;
import stam.testmigration.setup.ConfigurationRetriever;
import stam.testmigration.setup.SetupTargetApp;

import java.io.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class MethodMatcher {
    private String sourceDir=SetupTargetApp.getSourceDir(), targetDir=SetupTargetApp.getTargetDir();
    static ArrayList<String> processedHelperClasses = new ArrayList<>();
    static Map<String, String> similarMethods = new HashMap<>();
    static Map<MethodDeclaration, MethodDeclaration> similarMethodDecl = new HashMap<>();
    static Map<String, String> targetMethodAndClass = new HashMap<>();
    static Multimap<String, String> sourceTargetClass = ArrayListMultimap.create();
    static ArrayList<MethodCallExpr> helperCallExprs = new ArrayList<>();
    static ArrayList<MethodCallExpr> javaAPIs = new ArrayList<>();
    private final Map<MethodDeclaration, Double> targetScore = new HashMap<>();
    private Map<MethodDeclaration, String> sourceMethods = new HashMap<>();

    void matchMethods(CompilationUnit modifiedTestCU) {
        setProjectRootDir(sourceDir, true);
        setProjectRootDir(targetDir, false);

        sourceMethods= getMethodsCalledInTestClass(modifiedTestCU);
        for(Map.Entry<MethodDeclaration, String> entry: sourceMethods.entrySet()){
            MethodDeclaration sourceMethod = entry.getKey();
            String sourceClass = entry.getValue();

            if(isSourceTestMethod(sourceMethod, sourceClass)) continue;
            calculateSimilarity(sourceMethod, sourceClass);
        }

        for(Map.Entry<MethodDeclaration, MethodDeclaration> entry: similarMethodDecl.entrySet()){
            similarMethods.put(entry.getKey().getNameAsString(), entry.getValue().getNameAsString());
        }

        resolveSourceTargetClassPairs();
    }

    private void calculateSimilarity(MethodDeclaration sourceMethod, String sourceClass) {
        double finalScore = 0, finalClassScore = 0;
        MethodDeclaration target = null;
        File targetFile = null;

        Stack<File> stack = getTargetFilesStack();
        while(!stack.isEmpty()) {
            File child = stack.pop();
            if (child.isDirectory() && !isTestDir(child)) {
                for(File f : Objects.requireNonNull(child.listFiles())) stack.push(f);
            } else if (isJavaFile(child)) {
                String name = FilenameUtils.removeExtension(child.getName());
                try {
                    for(MethodDeclaration targetMethod: getMethodDecls(new JavaParser().parse(child).getResult().get())){
                        if(isTargetTestMethod(targetMethod, name)) continue;

                        double score = calculateVecSimilarity(sourceMethod, targetMethod);
                        double vecClassScore = calculateVecSimilarity(sourceClass, name);
                        double levClassScore = calculateLVSim(sourceClass, name);

                        if(targetScore.containsKey(targetMethod)){
                            if(targetScore.get(targetMethod)>=score) {
                                continue;
                            }else {
                                findAnotherTargetForSource(targetMethod);
                            }
                        }

                        if(sourceClass.equals(new CodeSearchResults().getSourceClassName())
                                && name.equals(new CodeSearchResults().getTargetClassName()) && score>ConfigurationRetriever.thresholdValue && score>finalScore){
                            finalScore = score;
                            target = targetMethod;
                            targetFile = child;
                        }else if(!sourceClass.equals(new CodeSearchResults().getSourceClassName())){
                            if(vecClassScore == 1 && score>ConfigurationRetriever.thresholdValue){
                                if(finalClassScore<1){
                                    finalScore = score;
                                    finalClassScore = vecClassScore;
                                    target = targetMethod;
                                    targetFile = child;
                                }else if(finalClassScore == 1 && score>finalScore){
                                    finalScore = score;
                                    target = targetMethod;
                                    targetFile = child;
                                }
                            }else if(finalClassScore != 1 && score>finalScore){
                                finalScore = score;
                                finalClassScore = vecClassScore;
                                target = targetMethod;
                                targetFile = child;
                            }else if(finalClassScore != 1 && score == finalScore && target != null){
                                //resolve with Levenshtein
                                double score1 = calculateLVSim(sourceMethod, target);
                                double score2 = calculateLVSim(sourceMethod, targetMethod);
                                if(score2>score1){
                                    finalClassScore = vecClassScore;
                                    target = targetMethod;
                                    targetFile = child;
                                }else if(score1 == score2){
                                    if(vecClassScore>finalClassScore){
                                        targetFile = child;
                                        target = targetMethod;
                                        finalClassScore = vecClassScore;
                                    }else if(vecClassScore == finalClassScore){
                                        double levClassScore2 = calculateLVSim(sourceClass, FilenameUtils.removeExtension(targetFile.getName()));
                                        if(levClassScore>levClassScore2){
                                            targetFile = child;
                                            target = targetMethod;
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }
            }
        }
        if(target != null){
            checkThresholdAndStoreSimMethods(finalScore, sourceMethod, target, sourceClass, targetFile);
        }
    }

    private boolean isTargetTestMethod(MethodDeclaration targetMethod, String targetClass){
        return (targetMethod.getNameAsString().equals(new CodeSearchResults().getTargetTestMethod())
                && targetClass.equals(new CodeSearchResults().getTargetClassName())) || isDeprecated(targetMethod);
    }

    private boolean isDeprecated(MethodDeclaration targetMethod){
        boolean exist = false;
        for(AnnotationExpr expr: targetMethod.getAnnotations()){
            if(expr.getNameAsString().equals("Deprecated")){
                exist = true;
                break;
            }
        }
        return exist;
    }

    private void findAnotherTargetForSource(MethodDeclaration targetMethod){
        MethodDeclaration source = null;
        for(Map.Entry<MethodDeclaration, MethodDeclaration> entry: similarMethodDecl.entrySet()){
            if(entry.getValue().equals(targetMethod)) {
                source = entry.getKey();
                break;
            }
        }
        if(source != null) {
            similarMethodDecl.remove(source);
            calculateSimilarity(source, sourceMethods.get(source));
        }
    }

    boolean isJavaFile(File file){
        return file.isFile() && FilenameUtils.getExtension(file.getName()).equals("java")
                && !FilenameUtils.removeExtension(file.getName()).endsWith("Test") && file.getAbsolutePath().contains("\\src\\");
    }

    boolean isTestDir(File file){
        return file.getAbsolutePath().contains("\\src\\test\\");
    }

    Stack<File> getTargetFilesStack(){
        Stack<File> stack = new Stack<>();
        String path = new SetupTargetApp().findFileOrDir(new File(targetDir), new CodeSearchResults().getTargetFileName());
        stack.push(new File(new File(path).getParent()));
        return stack;
    }

    private void checkThresholdAndStoreSimMethods(double finalScore, MethodDeclaration sourceMethod, MethodDeclaration target, String sourceClass, File targetFile){

        String targetMethod = target.getNameAsString();
        String targetTestMethod = new CodeSearchResults().getTargetTestMethod();
        String targetClass = FilenameUtils.removeExtension(targetFile.getName());

        if(finalScore>ConfigurationRetriever.thresholdValue && !targetMethod.equals(targetTestMethod)){
            targetScore.put(target, finalScore);
            similarMethodDecl.put(sourceMethod, target);
            if(!targetMethodAndClass.containsKey(targetMethod)){
                targetMethodAndClass.put(targetMethod, targetClass);
            }
            sourceTargetClass.put(sourceClass, targetClass);
            //if the file is in test directory of a different module, copy the file in the test directory of the working module (workaround solution)
            if(!targetFile.getAbsolutePath().startsWith(SetupTargetApp.getTargetDir()) && isTestDir(targetFile)){
                copyHelperClass(targetFile);
                processedHelperClasses.add(targetClass);
            }
        }
    }

    private void copyHelperClass(File helperFile){
        String testFile = SetupTargetApp.getTestFileNameInTarget();
        SetupTargetApp setupTargetApp = new SetupTargetApp();
        String path = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getTargetDir()), testFile);
        File testDir = new File(path).getParentFile();
        try {
            FileUtils.copyFileToDirectory(helperFile, testDir, false);
        } catch (IOException e) {
            e.printStackTrace();
        }
        File copiedFile = new File(testDir.getAbsoluteFile()+File.separator+helperFile.getName());
        CompilationUnit helperCU = SetupTargetApp.getCompilationUnit(copiedFile);
        String packageName = setupTargetApp.getPackageName(testFile, SetupTargetApp.getTargetDir());
        String importName = helperCU.getPackageDeclaration().get().getNameAsString();
        //addImport with asterisk not working directly, using setImport instead by adding and replacing a temp import
        helperCU.addImport("temp.replace.with.original");
        helperCU.setImport(helperCU.getImports().size()-1, new ImportDeclaration(importName, false, true));
        //replace package
        helperCU.setPackageDeclaration(packageName);
        new TestModifier().commitChanges(helperCU, copiedFile);
    }

    private double calculateVecSimilarity(MethodDeclaration sourceMethod, MethodDeclaration targetMethod){
        ArrayList<String> sourceWords = splitCamelCase(sourceMethod.getNameAsString());
        ArrayList<String> targetWords = splitCamelCase(targetMethod.getNameAsString());

        double totalScore = 0.0;
        for(String sourceWord: sourceWords){
            double score = 0.0;
            for(String targetWord: targetWords){
                double tempScore = ConfigurationRetriever.vec.similarity(sourceWord, targetWord);
                if(tempScore>score)
                    score = tempScore;
            }
            totalScore += score;
        }
        return totalScore/sourceWords.size();
    }

    double calculateVecSimilarity(String sourceMethod, String targetMethod){
        ArrayList<String> sourceWords = splitCamelCase(sourceMethod);
        ArrayList<String> targetWords = splitCamelCase(targetMethod);

        double totalScore = 0.0;
        for(String sourceWord: sourceWords){
            double score = 0.0;
            for(String targetWord: targetWords){
                double tempScore = ConfigurationRetriever.vec.similarity(sourceWord, targetWord);
                if(tempScore>score)
                    score = tempScore;
            }
            totalScore += score;
        }
        return totalScore/sourceWords.size();
    }

    private double calculateLVSim(MethodDeclaration sourceMethod, MethodDeclaration targetMethod){
        ArrayList<String> source, target;
        if(!sourceMethod.getNameAsString().equals(targetMethod.getNameAsString())){
            source = splitCamelCase(sourceMethod.getNameAsString());
            target = splitCamelCase(targetMethod.getNameAsString());
            splitWordFurther(source, target);
            removeDuplicates(source);
            removeDuplicates(target);
            removeMatchingWords(source, target);
        }else{
            source = new ArrayList<>();
            source.add(sourceMethod.getNameAsString());
            target = new ArrayList<>();
            target.add(targetMethod.getNameAsString());
        }

        String sourceMethodName = buildName(source);
        String targetMethodName = buildName(target);
        double maxLength;
        if(sourceMethod.getNameAsString().length() == targetMethod.getNameAsString().length()
                || sourceMethod.getNameAsString().length()>targetMethod.getNameAsString().length()){
            maxLength = sourceMethod.getNameAsString().length();
        }else{
            maxLength = targetMethod.getNameAsString().length();
        }
        double returnScore = 0.0;
        String sourceReturnType = getReturnType(sourceMethod);
        String targetReturnType = getReturnType(targetMethod);
        if(sourceReturnType.equals(targetReturnType))
            returnScore = 1.0;

        double paramScore = getParamSimilarityScore(sourceMethod, targetMethod);

        double distance = 1 - ((double) new LevenshteinDistance().apply(sourceMethodName, targetMethodName)/maxLength);
        return  (distance*0.8)+(returnScore*0.1)+(paramScore*0.1);
    }

    double calculateLVSim(String sourceClass, String targetClass){
        ArrayList<String> source, target;
        if(!sourceClass.equals(targetClass)){
            source = splitCamelCase(sourceClass);
            target = splitCamelCase(targetClass);
            splitWordFurther(source, target);
            removeDuplicates(source);
            removeDuplicates(target);
            removeMatchingWords(source, target);
        }else{
            source = new ArrayList<>();
            source.add(sourceClass);
            target = new ArrayList<>();
            target.add(targetClass);
        }

        String sourceClassName = buildName(source);
        String targetClassName = buildName(target);
        double maxLength;
        if(sourceClass.length() == targetClass.length() || sourceClass.length()>targetClass.length()){
            maxLength = sourceClass.length();
        }else{
            maxLength = targetClass.length();
        }

        return  1 - ((double) new LevenshteinDistance().apply(sourceClassName, targetClassName)/maxLength);
    }

    private double getParamSimilarityScore(MethodDeclaration sourceMethod, MethodDeclaration targetMethod){
        double paramScore;
        ArrayList<String> sourceParams = getParams(sourceMethod);
        ArrayList<String> targetParams = getParams(targetMethod);

        if(sourceParams.isEmpty() && targetParams.isEmpty()){
            paramScore = 1.0;
        }else{
            //divide equal weight among parameters
            double matchCount = 0;
            if(sourceParams.size() == targetParams.size() || sourceParams.size()>targetParams.size()){
                for(String type: targetParams){
                    if(sourceParams.contains(type)){
                        matchCount++;
                    }
                }
                paramScore = matchCount/sourceParams.size();
            }else{
                for(String type: sourceParams){
                    if(targetParams.contains(type)){
                        matchCount++;
                    }
                }
                paramScore = matchCount/targetParams.size();
            }
        }
        return paramScore;
    }

    private ArrayList<String> getParams(MethodDeclaration node){
        ArrayList<String> params = new ArrayList<>();
        for(Parameter parameter: node.getParameters()){
            String type = parameter.getTypeAsString();
            if(type.contains("Set") || type.contains("List") || type.contains("Map") || type.contains("Vector"))
                type = "Collection";
            params.add(type);
        }
        return params;
    }

    private String getReturnType(MethodDeclaration node){
        String type = node.getTypeAsString();
        if(type.contains("Set") || type.contains("List") || type.contains("Map") || type.contains("Vector"))
            type = "Collection";
        return type;
    }

    String buildName(ArrayList<String> words){
        StringBuilder name = new StringBuilder();
        for(String word: words){
            name.append(word);
        }
        return name.toString();
    }

    void removeMatchingWords(ArrayList<String> source, ArrayList<String> target){
        ArrayList<String> matchedWords = new ArrayList<>();
        for(String word: source){
            if(target.contains(word))
                matchedWords.add(word);
        }
        for(String word: matchedWords){
            source.remove(word);
            target.remove(word);
        }
    }

    void removeDuplicates(ArrayList<String> words){
        ArrayList<String> uniqueWords = new ArrayList<>();
        for(String word: words){
            if(!uniqueWords.contains(word))
                uniqueWords.add(word);
        }
        words.clear();
        words.addAll(uniqueWords);
    }

    void splitWordFurther(ArrayList<String> source, ArrayList<String> target){
        ArrayList<String> newTargetWords = new ArrayList<>();
        ArrayList<String> removeFromTarget = new ArrayList<>();
        ArrayList<String> newSourceWords = new ArrayList<>();
        ArrayList<String> removeFromSource = new ArrayList<>();

        for(String sourceWord: source){
            for(String targetWord: target){
                if(targetWord.contains(sourceWord)){
                    removeFromTarget.add(targetWord);
                    newTargetWords.add(targetWord.replace(sourceWord, ""));
                    newTargetWords.add(sourceWord);
                }
            }
        }

        for(String targetWord: target){
            for(String sourceWord: source){
                if(sourceWord.contains(targetWord)){
                    removeFromSource.add(sourceWord);
                    newSourceWords.add(sourceWord.replace(targetWord, ""));
                    newSourceWords.add(targetWord);
                }
            }
        }

        source.removeAll(removeFromSource);
        source.addAll(newSourceWords);
        target.removeAll(removeFromTarget);
        target.addAll(newTargetWords);

    }

    ArrayList<String> splitCamelCase(String name){
        ArrayList<String> words = new ArrayList<>();
        for(String word: name.split("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])")){
            if(!words.contains(word.toLowerCase()))
                words.add(word.toLowerCase());
        }
        return words;
    }

    private boolean hasVisibleForTestingAnnotation(MethodDeclaration node){
        for(AnnotationExpr annotationExpr: node.getAnnotations()){
            if(annotationExpr.getNameAsString().equals("VisibleForTesting"))
                return true;
        }
        return false;
    }

    //for module-based projects
    private void setProjectRootDir(String dir, boolean srcDir){
        String parentDir = new File(dir).getParent();
        if(parentDir != null){
            for(File file: Objects.requireNonNull(new File(parentDir).listFiles())){
                if(file.isFile() && (file.getName().equals("build.gradle") || file.getName().equals("pom.xml"))){
                    if(srcDir){
                        sourceDir = parentDir;
                    }else{
                        targetDir = parentDir;
                    }
                }
            }
        }
    }

    private Map<MethodDeclaration, String> getMethodsCalledInTestClass(CompilationUnit modifiedTestCU){
        CompilationUnit testCU = Objects.requireNonNull(getTestCompilationFromSourceApp());

        ArrayList<String> methodsCalledInModifiedTest = new ArrayList<>();
        getMethodsCalledInModifiedTest(modifiedTestCU, methodsCalledInModifiedTest);

        Map<MethodDeclaration, String> methodsCalledInTest = new HashMap<>();
        testCU.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodCallExpr callExpr, Object arg){
                super.visit(callExpr, arg);
                if(methodsCalledInModifiedTest.contains(callExpr.getNameAsString())){
                    try {
                        ResolvedMethodDeclaration expr = callExpr.resolve();
                        String qualifiedName = expr.getQualifiedName();
                        if(qualifiedName.startsWith("java")){
                            javaAPIs.add(callExpr);
                        }else{
                            String className = TestModifier.getFileNameOfInnerClass(expr.getClassName());
                            if(!getKeysByValue(methodsCalledInTest, className).contains(callExpr.getNameAsString()) && isNotHelper(className, callExpr)){
                                MethodDeclaration methodDeclaration = getMethodDeclaration(callExpr, className);
                                if(methodDeclaration != null){
                                    methodsCalledInTest.put(methodDeclaration, className);
                                }
                            }
                        }
                    }catch(RuntimeException ignored){}
                }
            }
        }, null);

        return methodsCalledInTest;
    }

    static boolean isNotHelper(String className, MethodCallExpr callExpr){
        String testPathString = File.separator+"test"+File.separator;
        String filePath = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getSourceDir()), className+".java");
        if(filePath != null && filePath.contains(testPathString)){
            if(callExpr != null){
                helperCallExprs.add(callExpr);
            }
            return false;
        }
        return true;
    }

    private ArrayList<String> getKeysByValue(Map<MethodDeclaration, String> methodsCalledInTest, String value){
        ArrayList<String> methodNames = new ArrayList<>();
        for(Map.Entry<MethodDeclaration, String> entry : methodsCalledInTest.entrySet()){
            if(entry.getValue().equals(value)){
                methodNames.add(entry.getKey().getNameAsString());
            }
        }
        return methodNames;
    }

    private MethodDeclaration getMethodDeclaration(MethodCallExpr callExpr, String className){
        ProjectRoot sourceProjectRoot = new SymbolSolverCollectionStrategy().collect(new File(sourceDir).toPath());
        final MethodDeclaration[] methodDeclaration = {null};
        for(SourceRoot sourceRoot: sourceProjectRoot.getSourceRoots()){
            try {
                sourceRoot.tryToParse();
            } catch (IOException ioException) {
                ioException.printStackTrace();
            }
            for(CompilationUnit compilationUnit: sourceRoot.getCompilationUnits()){
                if(compilationUnit.getTypes().size()>0 && compilationUnit.getType(0).resolve().getName().equals(className)){
                    compilationUnit.accept(new VoidVisitorAdapter<Object>() {
                        @Override
                        public void visit(MethodDeclaration declaration, Object arg){
                            super.visit(declaration, arg);
                            if(declaration.resolve().getQualifiedSignature().equals(callExpr.resolve().getQualifiedSignature())){
                                methodDeclaration[0] = declaration;
                            }
                        }
                    }, null);
                }
            }
        }

        return methodDeclaration[0];
    }

    private void getMethodsCalledInModifiedTest(CompilationUnit modifiedTestCU, ArrayList<String> methods){
        modifiedTestCU.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodCallExpr callExpr, Object arg){
                super.visit(callExpr, arg);
                String name = callExpr.getNameAsString();
                if(!methods.contains(name) && !name.equals("is") && !name.startsWith("assert")){
                    methods.add(name);
                }

            }
        }, null);
    }

    CompilationUnit getTestCompilationFromSourceApp(){
        ProjectRoot sourceProjectRoot = new SymbolSolverCollectionStrategy().collect(new File(sourceDir).toPath());
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

    private ArrayList<MethodDeclaration> getMethodDecls(CompilationUnit cu){
        ArrayList<MethodDeclaration> methodSign = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                if(!node.isPrivate() || hasVisibleForTestingAnnotation(node)){
                    methodSign.add(node);
                }
            }
        }, null);
        methodSign.addAll(getMethodsFromExtendedClass(cu));
        return methodSign;
    }

    //get methods from inherited classes
    ArrayList<MethodDeclaration> getMethodsFromExtendedClass(CompilationUnit cu){
        ArrayList<MethodDeclaration> methods = new ArrayList<>();
        for(ClassOrInterfaceType classType : Utilities.getExtendedTypes(cu)){
            String path = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getTargetDir()), classType+".java");
            if(path != null){
                CompilationUnit targetCU = SetupTargetApp.getCompilationUnit(new File(path));
                targetCU.accept(new VoidVisitorAdapter<Object>() {
                    @Override
                    public void visit(MethodDeclaration node, Object arg){
                        super.visit(node, arg);
                        if(!node.isPrivate() || hasVisibleForTestingAnnotation(node)){
                            methods.add(node);
                        }
                    }
                }, null);
            }
        }
        return methods;
    }

    private void resolveSourceTargetClassPairs(){
        Map<String, String> classPairs = new HashMap<>();
        for(String sourceClass: sourceTargetClass.keySet()){
            Map<String, Long> frequency = sourceTargetClass.get(sourceClass).stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            double value = 0;
            String target = null;
            for(String targetClass: frequency.keySet()){
                double num = frequency.get(targetClass);
                if(num>value){
                    value = num;
                    target = targetClass;
                }
            }
            //select a target class based on number of matching methods. If same number of matching methods, select based on vector similarity
            if(frequency.size()>1 && sameFrequency(frequency)){
                classPairs.put(sourceClass, getBestMatchingType(sourceClass, MethodMatcher.sourceTargetClass.get(sourceClass)));
            }else{
                classPairs.put(sourceClass, target);
            }
        }
        sourceTargetClass.clear();
        for(String key: classPairs.keySet()){
            sourceTargetClass.put(key, classPairs.get(key));
        }
    }

    private boolean sameFrequency(Map<String, Long> frequency){
        double value = new ArrayList<>(frequency.values()).get(0);
        for(long num : frequency.values()){
            if(num != value){
                return false;
            }
        }
        return true;
    }

    private String getBestMatchingType(String sourceType, Collection<String> targetTypes){
        double score = 0;
        String selectedTarget = null;
        for(String targetType: targetTypes){
            double vecScore = new MethodMatcher().calculateVecSimilarity(sourceType, targetType);
            if(vecScore>score){
                score = vecScore;
                selectedTarget = targetType;
            }
        }
        return selectedTarget;
    }

    private boolean isSourceTestMethod(MethodDeclaration sourceMethod, String sourceClass){
        return sourceMethod.getNameAsString().equals(new CodeSearchResults().getSourceTestMethod())
                && sourceClass.equals(new CodeSearchResults().getSourceClassName());
    }

}
