package stam.testmigration.setup;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.symbolsolver.utils.SymbolSolverCollectionStrategy;
import com.github.javaparser.utils.ProjectRoot;
import com.github.javaparser.utils.SourceRoot;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.model.Model;
import stam.testmigration.main.TestModifier;
import stam.testmigration.search.CodeSearchResults;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Random;
import java.util.Stack;

public class SetupTargetApp {
    private static String sourceDir, targetDir, gradlePath, mavenPath, jdkRootDir, buildType;
    CodeSearchResults searchResults = new CodeSearchResults();
    private static String testFileNameInTarget;

    public void setupTarget() {
        String sourceBuildType = getBuildType(sourceDir);
        String targetBuildType = getBuildType(targetDir);

        File targetTestJavaDir = checkTestFolder(targetBuildType);

        File packageDir = createPackageDir(targetTestJavaDir);
        copyTestFiles(packageDir);

        if(sourceBuildType == null || targetBuildType == null){
            System.out.println("TestMigrator only supports Maven or Gradle-build projects.");
            System.exit(0);
        }
        else if(targetBuildType.equals("mavenAndGradle")){
            buildType = "maven";
            if(sourceBuildType.equals("gradle")){
                new GradleUpdater().updateTargetGradle();
                new GradleToPomDependencyUpdater().updatePOMFile();
            }else if(sourceBuildType.equals("maven")){
                new POMUpdater().updatePOMFile();
                new PomToGradleDependencyUpdater().updateGradle();
            }else if(sourceBuildType.equals("mavenAndGradle")){
                new GradleUpdater().updateTargetGradle();
                new GradleToPomDependencyUpdater().updatePOMFile();
                new POMUpdater().updatePOMFile();
                new PomToGradleDependencyUpdater().updateGradle();
            }else{
                System.out.println("The tool only supports test migration between Gradle or Maven-build projects.");
                System.exit(0);
            }
        }
        else if((sourceBuildType.equals("gradle") || sourceBuildType.equals("mavenAndGradle")) && targetBuildType.equals("gradle")){
            buildType = "gradle";
            new GradleUpdater().updateTargetGradle();
        }
        else if((sourceBuildType.equals("maven") || sourceBuildType.equals("mavenAndGradle")) && targetBuildType.equals("maven")){
            buildType = "maven";
            new POMUpdater().updatePOMFile();
        }else if((sourceBuildType.equals("maven") || sourceBuildType.equals("mavenAndGradle")) && targetBuildType.equals("gradle")){
            buildType = "gradle";
            new PomToGradleDependencyUpdater().updateGradle();
        }else if((sourceBuildType.equals("gradle") || sourceBuildType.equals("mavenAndGradle")) && targetBuildType.equals("maven")){
            buildType = "maven";
            new GradleToPomDependencyUpdater().updatePOMFile();
        }else{
            System.out.println("The tool only supports test migration between Gradle or Maven-build projects.");
            System.exit(0);
        }

    }

    private String getBuildType(String projectRootDir){
        String type = null;

        String gradlePath = findFileOrDir(new File(projectRootDir), "build.gradle");
        String pomPath = findFileOrDir(new File(projectRootDir), "pom.xml");
        if(gradlePath != null && pomPath == null){
            type = "gradle";
        }else if(pomPath != null && gradlePath == null){
            type = "maven";
        }else if(pomPath != null && gradlePath != null){
            type = "mavenAndGradle";
        }

        return type;
    }

    //copy test files from source app to target app
    private void copyTestFiles(File toDir) {
        String path = findTestFile(new File(sourceDir), searchResults.getTestFileName());

        String targetClassName = searchResults.getTargetClassName();
        generateTestFileName(targetClassName, toDir);

        File targetTestFile = null;
        try {
            targetTestFile = new File(toDir.getAbsolutePath()+File.separator+testFileNameInTarget);
            targetTestFile.createNewFile();
            FileUtils.copyFile(new File(path), targetTestFile);
        } catch (IOException e) {
            e.printStackTrace();
        }

        //change class name
        CompilationUnit compilationUnit = getCompilationUnit(targetTestFile);
        String name = testFileNameInTarget.substring(0, testFileNameInTarget.lastIndexOf("."));
        compilationUnit.getType(0).getName().setIdentifier(name);
        new TestModifier().commitChanges(compilationUnit, targetTestFile);

    }

    private void generateTestFileName(String targetClassName, File testFile){
        String targetMethodName = StringUtils.capitalize(searchResults.getTargetTestMethod());
        testFileNameInTarget = TestModifier.getFileNameOfInnerClass(targetClassName)+targetMethodName+"Test.java";
        if(new File(testFile.getParent()+File.separator+testFileNameInTarget).exists()){
            testFileNameInTarget = TestModifier.getFileNameOfInnerClass(targetClassName)+targetMethodName+"Test"+new Random().nextInt(100)+".java";
        }
    }

    //create package in test dir
    private File createPackageDir(File file) {
        String packageName = getPackageName(searchResults.getTargetFileName(), targetDir);
        packageName = packageName.replaceAll("\\.", "\\\\");

        File dirs = new File(file.getAbsolutePath()+File.separator+packageName);
        dirs.mkdirs();

        return dirs;
    }

    //get package name from class file
    public String getPackageName(String fileName, String dir) {
        String path = findFileOrDir(new File(dir), fileName);
        CompilationUnit cu = getCompilationUnit(new File(path));

        return cu.getPackageDeclaration().get().getNameAsString();
    }

    public static CompilationUnit getCompilationUnit(File file) {

        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
        combinedTypeSolver.add(new ReflectionTypeSolver());

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);
        ParserConfiguration parserConfiguration = new ParserConfiguration();
        parserConfiguration.setSymbolResolver(symbolSolver);
        JavaParser parser = new JavaParser(parserConfiguration);

        CompilationUnit cu = null;
        try {
            cu = parser.parse(file).getResult().get();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        return cu;
    }

    //create test/java dir if it does not exist in src dir of the target app
    private File checkTestFolder(String buildType) {

        File targetTestJavaDir = null;

        String path = findFileOrDir(new File(targetDir), "src");
        if(path != null) {
            File[] files = new File(path).listFiles();
            for(File file: files) {
                if(file.getName().equals("test")) {
                    targetTestJavaDir = new File(file+File.separator+"java");
                }
            }
        }

        if(targetTestJavaDir == null){
            targetTestJavaDir = checkCustomFolderSetting(buildType);
        }

        if(targetTestJavaDir == null) {
            new File(path+File.separator+"test"+File.separator+"java").mkdirs();
            targetTestJavaDir = new File(path+File.separator+"test"+File.separator+"java");
        }

        return targetTestJavaDir;
    }

    private File checkCustomFolderSetting(String buildType){
        if(buildType.equals("maven")){
            return mavenCustomTestFolder();
        }else if(buildType.equals("gradle")){
            //TODO
        }
        return null;
    }

    private File mavenCustomTestFolder(){
        POMUpdater pomUpdater = new POMUpdater();

        String pomPath = findFileOrDir(new File(targetDir), "pom.xml");
        Model mavenModel = pomUpdater.getPomModel(new File(pomPath));

        File parentPom = pomUpdater.getParentPom(targetDir);

        if(mavenModel.getBuild() != null && mavenModel.getBuild().getTestSourceDirectory() != null){
            String testDir = mavenModel.getBuild().getTestSourceDirectory();
            String testDirPath = targetDir+File.separator+testDir;
            return new File(testDirPath);
        }else if(parentPom != null){
            Model parentModel = pomUpdater.getPomModel(parentPom);
            if(parentModel.getBuild() != null && parentModel.getBuild().getTestSourceDirectory() != null){
                String testDir = parentModel.getBuild().getTestSourceDirectory();
                String testDirPath = targetDir+File.separator+testDir;
                return new File(testDirPath);
            }
        }
        return null;
    }

    //get path of src dir
    public String findFileOrDir(File root, String name) {

        if (root.getName().equals(name) && isIntendedFile(root, name)) return root.getAbsolutePath();
        File[] files = root.listFiles();
        if(files != null) {
            for (File file : files) {
                if(file.isDirectory()) {
                    String path = findFileOrDir(file, name);
                    if (path != null) return path;
                } else if(file.getName().equals(name) && isIntendedFile(file, name)){
                    return file.getAbsolutePath();
                }
            }
        }

        return null;
    }

    public String findTestFile(File root, String name) {
        ArrayList<String> filePathList = new ArrayList<>();
        if (root.getName().equals(name) && isIntendedFile(root, name)) filePathList.add(root.getAbsolutePath());

        Stack<File> stack = new Stack<>();
        stack.push(root);
        while(!stack.isEmpty()){
            File child = stack.pop();
            if (child.isDirectory()) {
                for(File f : Objects.requireNonNull(child.listFiles())) stack.push(f);
            }else if(child.getName().equals(name) && isIntendedFile(child, name)){
                filePathList.add(child.getAbsolutePath());
            }
        }
        return selectTestFile(filePathList);
    }

    //select a test file if multiple test files exist
    private String selectTestFile(ArrayList<String> filePathList){
        if(filePathList.isEmpty()){
            return null;
        }else if(filePathList.size()>1){
            String sourcePath = findFileOrDir(new File(sourceDir), searchResults.getSourceFileName());
            String dir = sourcePath.substring(0, sourcePath.lastIndexOf(File.separator));
            for(String path : filePathList){
                String testDir = path.substring(0, path.lastIndexOf(File.separator));
                if(testDir.equals(dir)){
                    return path;
                }
            }
        }
        return filePathList.get(0);
    }

    private boolean isIntendedFile(File file, String name){
        if(file.getAbsolutePath().contains(sourceDir) && name.equals(searchResults.getSourceFileName())){
            CompilationUnit sourceCU = getCompilationUnit(file);
            return hasMethod(sourceCU, searchResults.getSourceTestMethod());
        }else if(file.getAbsolutePath().contains(targetDir) && name.equals(searchResults.getTargetFileName())){
            CompilationUnit targetCU = getCompilationUnit(file);
            return hasMethod(targetCU, searchResults.getTargetTestMethod());
        }else if(name.equals(searchResults.getTestFileName())){
            CompilationUnit sourceTestCU = getCompilationUnit(file);
            return isMethodCalled(sourceTestCU, searchResults.getSourceTestMethod());
        }

        return true;
    }

    private boolean isMethodCalled(CompilationUnit unit, String methodName){
        final boolean[] containsMethod = {false};
        unit.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodCallExpr callExpr, Object arg){
                super.visit(callExpr, arg);
                if(callExpr.getNameAsString().equals(methodName)){
                    containsMethod[0] = true;
                }
            }
        }, null);
        return containsMethod[0];
    }

    private boolean hasMethod(CompilationUnit unit, String methodName){
        final boolean[] containsMethod = {false};
        unit.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                if(node.getNameAsString().equals(methodName)){
                    containsMethod[0] = true;
                }
            }
        }, null);
        return containsMethod[0];
    }

    public static CompilationUnit getTestCompilationFromTargetApp(File testFile){
        SymbolSolverCollectionStrategy symbolSolverCollectionStrategy = new SymbolSolverCollectionStrategy();
        ProjectRoot sourceProjectRoot = symbolSolverCollectionStrategy.collect(new File(targetDir).toPath());
        String testFileName = testFile.getName();
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

    public static String getSourceDir() {
        return sourceDir;
    }

    public static String getTargetDir() {
        return targetDir;
    }

    public static String getGradlePath() {
        return gradlePath;
    }

    public static String getMavenPath() {
        return mavenPath;
    }

    public static String getJdkRootDir() {
        return jdkRootDir;
    }

    public static String getBuildType() {
        return buildType;
    }

    public static String getTestFileNameInTarget() {
        return testFileNameInTarget;
    }

    public static void setSourceDir(String sourceDir) {
        SetupTargetApp.sourceDir = sourceDir;
    }

    public static void setTargetDir(String targetDir) {
        SetupTargetApp.targetDir = targetDir;
    }

    public static void setGradlePath(String gradlePath) {
        SetupTargetApp.gradlePath = gradlePath;
    }

    public static void setMavenPath(String mavenPath) {
        SetupTargetApp.mavenPath = mavenPath;
    }

    public static void setJdkRootDir(String jdkRootDir) {
        SetupTargetApp.jdkRootDir = jdkRootDir;
    }
}
