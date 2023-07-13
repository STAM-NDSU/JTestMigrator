package stam.testmigration.main;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.symbolsolver.utils.SymbolSolverCollectionStrategy;
import com.github.javaparser.utils.ProjectRoot;
import com.github.javaparser.utils.SourceRoot;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.text.similarity.LevenshteinDistance;
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
    private final Map<MethodDeclaration, Double> targetScore = new HashMap<>();

    void matchMethods() {
        setProjectRootDir(sourceDir, true);
        setProjectRootDir(targetDir, false);

        for(MethodDeclaration sourceMethod : new HashSet<>(MethodCallResolver.resolvedCalls.values())){
            String sourceClass = sourceMethod.resolve().getClassName();
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

        Stack<File> stack = getTargetFilesStack(sourceClass);
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

                        if(targetScore.containsKey(targetMethod) && targetScore.get(targetMethod)>=score && !isOverloaded(sourceMethod, targetMethod)) continue;
                        if(sourceClass.equals(new CodeSearchResults().getSourceClassName())
                                && name.equals(new CodeSearchResults().getTargetClassName()) && score>ConfigurationRetriever.thresholdValue && score>finalScore){
                            finalScore = score;
                            target = targetMethod;
                            targetFile = child;
                        }else if(!sourceClass.equals(new CodeSearchResults().getSourceClassName())){
                            double vecClassScore = calculateVecSimilarity(sourceClass, name);
                            double levClassScore = calculateLVSim(sourceClass, name);
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
            if(targetScore.containsKey(target) && targetScore.get(target)<finalScore) findAnotherTargetForSource(target);
            checkThresholdAndStoreSimMethods(finalScore, sourceMethod, target, sourceClass, targetFile);
        }
    }

    private boolean isOverloaded(MethodDeclaration source, MethodDeclaration target){
        for(Map.Entry<MethodDeclaration, MethodDeclaration> entry: similarMethodDecl.entrySet()){
            MethodDeclaration src = entry.getKey();
            MethodDeclaration trg = entry.getValue();
            if(target.getDeclarationAsString().equals(trg.getDeclarationAsString()) && source.getNameAsString().equals(src.getNameAsString())
                    && source.getParameters().size()==src.getParameters().size()) return true;
        }
        return false;
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
            calculateSimilarity(source, source.resolve().getClassName());
        }
    }

    boolean isJavaFile(File file){
        return file.isFile() && FilenameUtils.getExtension(file.getName()).equals("java")
                && !FilenameUtils.removeExtension(file.getName()).endsWith("Test") && file.getAbsolutePath().contains("\\src\\");
    }

    boolean isTestDir(File file){
        return file.getAbsolutePath().contains("\\src\\test\\");
    }

    Stack<File> getTargetFilesStack(String className){
        Stack<File> stack = new Stack<>();
        String path = new SetupTargetApp().findFileOrDir(new File(targetDir), new CodeSearchResults().getTargetFileName());
        if(className.equals(new CodeSearchResults().getSourceClassName())){
            //look for similar methods in only target class
            stack.push(new File(path));
        }else{
            //look for similar methods in the target class package
            stack.push(new File(new File(path).getParent()));
        }
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
        new TestCodeTransformer().commitChanges(helperCU, copiedFile);
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
        double vecScore = totalScore/sourceWords.size();
        double typeScore = getTypeSimilarityScore(sourceMethod, targetMethod);
        return (vecScore*0.5)+(typeScore*0.5);
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

        double distance = 1 - ((double) new LevenshteinDistance().apply(sourceMethodName, targetMethodName)/maxLength);
        double typeScore = getTypeSimilarityScore(sourceMethod, targetMethod);
        return  (distance*0.5)+(typeScore*0.5);
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

    private double getTypeSimilarityScore(MethodDeclaration sourceMethod, MethodDeclaration targetMethod){
        ArrayList<String> sourceParams = getParams(sourceMethod);
        ArrayList<String> targetParams = getParams(targetMethod);
        double score = 0;
        if(sourceParams.isEmpty() && targetParams.isEmpty()) return 1.0;
        if(sourceParams.size() == targetParams.size()){
            //divide equal weight among parameters
            for(int i=0; i<sourceParams.size(); i++){
                String sourceParam = sourceParams.get(i);
                String targetParam = targetParams.get(i);
                if(sourceParam.equals(targetParam)){
                    score++;
                }else if(new InputTypeFilter().compatibleTypeExists(sourceParam, new ArrayList<>(Collections.singleton(targetParam))) ){
                    score=score+0.6;
                }
            }
            return score/(sourceParams.size());
        }
        return score;
    }

    private ArrayList<String> getParams(MethodDeclaration node){
        ArrayList<String> params = new ArrayList<>();
        for(Parameter parameter: node.getParameters()){
            String type = parameter.getTypeAsString();
            if(type.contains("Set") || type.contains("List") || type.contains("Map") || type.contains("Vector")) type = "Collection";
            params.add(type);
        }
        return params;
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
