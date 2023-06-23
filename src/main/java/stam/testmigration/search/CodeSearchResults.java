package stam.testmigration.search;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import stam.testmigration.setup.SetupTargetApp;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CodeSearchResults {
    //TODO: populate values automatically
    //methods should also contain signatures to distinguish overloaded methods
    private static String sourceFileName, sourceTestMethod;
    private static String targetFileName, targetTestMethod;
    private static String targetTestFileName, testFileName;
    private static String[] testsToMigrate;

    public String getSourceFileName() {
        long dotCount = sourceFileName.chars().filter(ch -> ch == '.').count();
        if(dotCount > 1){
            return sourceFileName.substring(0, sourceFileName.indexOf("."))+".java";
        }
        return sourceFileName;
    }

    public String getSourceClassName(){
        long dotCount = sourceFileName.chars().filter(ch -> ch == '.').count();
        final String[] name = {sourceFileName.substring(0, sourceFileName.lastIndexOf("."))};
        if(dotCount > 1){
            String path = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getSourceDir()), testFileName);
            if(path != null){
                CompilationUnit testCU = SetupTargetApp.getCompilationUnit(new File(path));
                testCU.accept(new VoidVisitorAdapter<Object>() {
                    @Override
                    public void visit(ImportDeclaration node, Object arg){
                        super.visit(node, arg);
                        if(node.getNameAsString().contains(name[0])){
                            name[0] = name[0].substring(name[0].indexOf(".")+1);
                        }
                    }
                }, null);
            }
        }
        return name[0];
    }

    public String getTestFileName() {
        return testFileName;
    }

    public String getSourceTestMethod() {
        return sourceTestMethod;
    }

    public String getTargetFileName() {
        long dotCount = targetFileName.chars().filter(ch -> ch == '.').count();
        if(dotCount > 1){
            return targetFileName.substring(0, targetFileName.indexOf("."))+".java";
        }
        return targetFileName;
    }

    public String getTargetClassName(){
        return targetFileName.substring(0, targetFileName.lastIndexOf("."));
    }

    public String getTargetTestMethod() {
        return targetTestMethod;
    }

    public String getTargetTestFileName() {
        return targetTestFileName;
    }

    public static void setSourceFileName(String sourceFileName) {
        CodeSearchResults.sourceFileName = sourceFileName;
    }

    public static void setSourceTestMethod(String sourceTestMethod) {
        CodeSearchResults.sourceTestMethod = sourceTestMethod;
    }

    public static void setTargetFileName(String targetFileName) {
        CodeSearchResults.targetFileName = targetFileName;
    }

    public static void setTargetTestMethod(String targetTestMethod) {
        CodeSearchResults.targetTestMethod = targetTestMethod;
    }

    public static void setTestFileName(String testFileName) {
        CodeSearchResults.testFileName = testFileName;
    }

    public static void setTargetTestFileName(String targetTestFileName) {
        CodeSearchResults.targetTestFileName = targetTestFileName;
    }

    public static void setTestsToMigrate(String testNames){
        if(!testNames.isEmpty()){
            testsToMigrate = testNames.split(";");
        }
    }

    public static List<String> getTestsToMigrate(){
        if(testsToMigrate == null) return new ArrayList<>();
        return Arrays.asList(testsToMigrate);
    }
}
