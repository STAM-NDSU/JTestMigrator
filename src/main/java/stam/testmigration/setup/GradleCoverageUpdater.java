package stam.testmigration.setup;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import stam.testmigration.search.CodeSearchResults;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class GradleCoverageUpdater {
    SetupTargetApp setupTargetApp = new SetupTargetApp();
    GradleUpdater gradleUpdater = new GradleUpdater();

    private boolean testNodeExists = false;
    private int testNodeLineNum;
    private int testNodeLastLineNum;

    private boolean jacocoNodeExists = false;
    private int jacocoNodeLineNum;
    private int jacocoNodeLastLineNum;

    private boolean externalTestTaskExists = false;
    private int externalTestTaskLineNum;

    static ArrayList<String> originalGradleContents = new ArrayList<>();

    public void addPluginAndTasks(String testFileName, boolean first){
        SetupTargetApp targetApp = new SetupTargetApp();
        File targetGradleFile = new GradleUpdater().getGradleFile(SetupTargetApp.getTargetDir(), targetApp);

        addCoverageFilter(targetGradleFile, testFileName, first);
    }

    private void addCoverageFilter(File targetGradleFile, String testFileName, boolean first){
        String packageName = setupTargetApp.getPackageName(SetupTargetApp.getTestFileNameInTarget(), SetupTargetApp.getTargetDir());

        List<String> gradleFileContents;
        checkNodes(targetGradleFile);
        gradleFileContents = getGradleContent(targetGradleFile, true);


        if(externalTestTaskExists){
            gradleFileContents.remove(externalTestTaskLineNum-1);
        }

        updateGradleFile(targetGradleFile, gradleFileContents);
        checkNodes(targetGradleFile);
        gradleFileContents = getGradleContent(targetGradleFile, false);

        String oldTestPackageName = new SetupTargetApp()
                .getPackageName(new CodeSearchResults()
                        .getTargetFileName(), SetupTargetApp.getTargetDir());
        String oldTestFileName = new CodeSearchResults().getTargetTestFileName();
        testFileName = testFileName.substring(0, testFileName.indexOf("."));

        if(first){
            oldTestFileName =  oldTestFileName.substring(0, testFileName.indexOf("."));
            if(testNodeExists){
                gradleFileContents.add(testNodeLineNum-1, "apply plugin: 'jacoco'");
                gradleFileContents.add(testNodeLastLineNum,"\tignoreFailures = true");
                gradleFileContents.add(testNodeLastLineNum+1, "\tfilter{");
                gradleFileContents.add(testNodeLastLineNum+2, "\t\tincludeTestsMatching \""+oldTestPackageName+"."+oldTestFileName+"\"");
                gradleFileContents.add(testNodeLastLineNum+3, "\t}");
                gradleFileContents.add(testNodeLastLineNum+4,"\ttest.finalizedBy(jacocoTestReport)");
            }else{
                gradleFileContents.add("");
                gradleFileContents.add("apply plugin: 'jacoco'");
                gradleFileContents.add("test {");
                gradleFileContents.add("\tignoreFailures = true");
                gradleFileContents.add("\tfilter{");
                gradleFileContents.add("\t\tincludeTestsMatching \""+oldTestPackageName+"."+oldTestFileName+"\"");
                gradleFileContents.add("\t}");
                gradleFileContents.add("\ttest.finalizedBy(jacocoTestReport)");
                gradleFileContents.add("}");
            }
        }else{
            if(testNodeExists){
                gradleFileContents.add(testNodeLineNum-1, "apply plugin: 'jacoco'");
                if(new CodeSearchResults().getTargetTestFileName() == null
                        || new CodeSearchResults().getTargetTestFileName().equals("")){
                    gradleFileContents.add(testNodeLastLineNum,"\tignoreFailures = true");
                    gradleFileContents.add(testNodeLastLineNum+1, "\tfilter{");
                    gradleFileContents.add(testNodeLastLineNum+2, "\t\tincludeTestsMatching \""+packageName+"."+testFileName+"\"");
                    gradleFileContents.add(testNodeLastLineNum+3, "\t}");
                    gradleFileContents.add(testNodeLastLineNum+4,"\ttest.finalizedBy(jacocoTestReport)");
                }else{
                    oldTestFileName =  oldTestFileName.substring(0, testFileName.indexOf("."));
                    gradleFileContents.add(testNodeLastLineNum,"\tignoreFailures = true");
                    gradleFileContents.add(testNodeLastLineNum+1, "\tfilter{");
                    gradleFileContents.add(testNodeLastLineNum+2, "\t\tincludeTestsMatching \""+packageName+"."+testFileName+"\"");
                    gradleFileContents.add(testNodeLastLineNum+3, "\t\tincludeTestsMatching \""+oldTestPackageName+"."+oldTestFileName+"\"");
                    gradleFileContents.add(testNodeLastLineNum+4, "\t}");
                    gradleFileContents.add(testNodeLastLineNum+5,"\ttest.finalizedBy(jacocoTestReport)");
                }
            }else{
                gradleFileContents.add("");
                gradleFileContents.add("apply plugin: 'jacoco'");
                gradleFileContents.add("test {");
                gradleFileContents.add("\tignoreFailures = true");
                gradleFileContents.add("\tfilter{");
                if(new CodeSearchResults().getTargetTestFileName() == null
                        || new CodeSearchResults().getTargetTestFileName().equals("")){
                    gradleFileContents.add("\t\tincludeTestsMatching \""+packageName+"."+testFileName+"\"");
                }else{
                    oldTestFileName =  oldTestFileName.substring(0, testFileName.indexOf("."));
                    gradleFileContents.add("\t\tincludeTestsMatching \""+packageName+"."+testFileName+"\"");
                    gradleFileContents.add("\t\tincludeTestsMatching \""+oldTestPackageName+"."+oldTestFileName+"\"");
                }
                gradleFileContents.add("\t}");
                gradleFileContents.add("\ttest.finalizedBy(jacocoTestReport)");
                gradleFileContents.add("}");
            }
        }


        updateGradleFile(targetGradleFile, gradleFileContents);
        checkNodes(targetGradleFile);
        gradleFileContents = getGradleContent(targetGradleFile, false);

        if(jacocoNodeExists){
            for(int i = jacocoNodeLineNum; i<jacocoNodeLastLineNum; i++){
                if(gradleFileContents.get(i).contains("xml.enabled false")){
                    gradleFileContents.set(i, "xml.enabled true");
                }
            }
            gradleFileContents.add(jacocoNodeLastLineNum-1, "dependsOn test");
        }else{
            gradleFileContents.add("");
            gradleFileContents.add("jacocoTestReport {");
            gradleFileContents.add("\tdependsOn test");
            gradleFileContents.add("\treports {");
            gradleFileContents.add("\t\txml.enabled true");
            gradleFileContents.add("\t}");
            gradleFileContents.add("}");
        }

        updateGradleFile(targetGradleFile, gradleFileContents);

    }

    private void checkNodes(File targetGradleFile){
        List<ASTNode> nodes = gradleUpdater.getNodes(targetGradleFile);
        nodes.forEach(node -> node.visit(new GradleCoverageUpdater.TestChecker()));
    }

    private List<String> getGradleContent(File targetGradleFile, boolean first){
        List<String> gradleFileContents = new ArrayList<>();
        try {
            gradleFileContents = Files.readAllLines( Paths.get( targetGradleFile.toURI() ) );
            if(first){
                originalGradleContents.addAll(gradleFileContents);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return gradleFileContents;
    }

    private void updateGradleFile(File targetGradleFile, List<String> gradleFileContents){
        try {
            Files.write(targetGradleFile.toPath(), gradleFileContents, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private class TestChecker extends CodeVisitorSupport {
        @Override
        public void visitMethodCallExpression(MethodCallExpression methodCall){
            super.visitMethodCallExpression(methodCall);
            if(methodCall.getMethodAsString().equals("test")){
                testNodeExists = true;
                testNodeLineNum = methodCall.getLineNumber();
                testNodeLastLineNum = methodCall.getLastLineNumber();
            } else if(methodCall.getMethodAsString().equals("jacocoTestReport")){
                jacocoNodeExists = true;
                jacocoNodeLineNum = methodCall.getLineNumber();
                jacocoNodeLastLineNum = methodCall.getLastLineNumber();
            } else if(methodCall.getArguments().getText().contains("testing.gradle") &&
                        methodCall.getMethodAsString().equals("apply")){
                //([from:$rootDir/gradle/jmh.gradle])
                externalTestTaskExists = true;
                externalTestTaskLineNum = methodCall.getLineNumber();
            }
        }
    }
    private String getTestFileName(){
        String testFileName = SetupTargetApp.getTestFileNameInTarget();
        return testFileName.substring(0, testFileName.indexOf("."));
    }

    public void removeTestFilter() {
        File targetGradleFile = gradleUpdater.getGradleFile(SetupTargetApp.getTargetDir(), setupTargetApp);
        updateGradleFile(targetGradleFile, originalGradleContents);
    }
}
