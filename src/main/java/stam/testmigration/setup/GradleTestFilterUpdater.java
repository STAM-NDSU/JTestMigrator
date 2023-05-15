package stam.testmigration.setup;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.expr.MethodCallExpression;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class GradleTestFilterUpdater {
    SetupTargetApp setupTargetApp = new SetupTargetApp();
    GradleUpdater gradleUpdater = new GradleUpdater();

    private boolean testNodeExists = false;
    private int testNodeLineNum;

    static ArrayList<String> originalGradleContents = new ArrayList<>();
    static boolean filterAdded = false;

    public void addTestFilter(String testFileName){
        filterAdded = true;
        File targetGradleFile = gradleUpdater.getGradleFile(SetupTargetApp.getTargetDir(), setupTargetApp);
        List<ASTNode> nodes = gradleUpdater.getNodes(targetGradleFile);
        nodes.forEach(node -> node.visit(new TestChecker()));

        String packageName = setupTargetApp.getPackageName(SetupTargetApp.getTestFileNameInTarget(), SetupTargetApp.getTargetDir());

        List<String> gradleFileContents = new ArrayList<>();
        try {
            gradleFileContents = Files.readAllLines( Paths.get( targetGradleFile.toURI() ) );
            originalGradleContents.addAll(gradleFileContents);
        } catch (IOException e) {
            e.printStackTrace();
        }

        if(testNodeExists){
            gradleFileContents.add(testNodeLineNum-1, "\tfilter.includeTestsMatching \""+packageName+"."+testFileName+"\"");
        }else{
            gradleFileContents.add("");
            gradleFileContents.add("test {");
            gradleFileContents.add("\tfilter.includeTestsMatching \""+packageName+"."+testFileName+"\"");
            gradleFileContents.add("}");
        }

        updateGradleFile(targetGradleFile, gradleFileContents);
    }

    public void removeTestFilter(){
        File targetGradleFile = gradleUpdater.getGradleFile(SetupTargetApp.getTargetDir(), setupTargetApp);
        if(filterAdded){
            updateGradleFile(targetGradleFile, originalGradleContents);
        }
    }

    private void updateGradleFile(File targetGradleFile, List<String> gradleFileContents){
        try {
            Files.write(targetGradleFile.toPath(), gradleFileContents, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    //check test node exists
    private class TestChecker extends CodeVisitorSupport {
        @Override
        public void visitMethodCallExpression(MethodCallExpression methodCall){
            super.visitMethodCallExpression(methodCall);
            if(methodCall.getMethodAsString().equals("test")){
                testNodeExists = true;
                testNodeLineNum = methodCall.getLastLineNumber();
            }
        }
    }
}
