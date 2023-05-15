package stam.testmigration.setup;

import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestDependencyUpdater extends CodeVisitorSupport {

    private final List<String> sourceDependencies;
    private final List<String> targetDependencies = new ArrayList<>();
    private final File targetGradleFile;

    private int dependencyStartLine = -1;
    private int dependencyLineNum = -1;
    private int columnNum = -1;

    private final Map<String, String> variables = new HashMap<>();

    private boolean testOptionsExists = false;
    private int androidLineNum;

    private boolean useAndroidx = false;

    public TestDependencyUpdater(File targetGradleFile, List<String> sourceDependencies){
        this.sourceDependencies = sourceDependencies;
        this.targetGradleFile = targetGradleFile;
    }

    //store all variables and their values
    @Override
    public void visitBinaryExpression(BinaryExpression binaryExpression){
        super.visitBinaryExpression(binaryExpression);
        variables.put(binaryExpression.getLeftExpression().getText(), binaryExpression.getRightExpression().getText());
    }

    //get dependencies node line num and test dependencies from target gradle file
    @Override
    public void visitMethodCallExpression(MethodCallExpression methodCall){
        String methodName = methodCall.getMethodAsString();

        //check dependencies node exists
        if(methodName.equals("dependencies")){
            dependencyStartLine = methodCall.getLineNumber();
            dependencyLineNum = methodCall.getLastLineNumber();
        }

        //check testOptions node exists
        if(methodName.equals("testOptions"))
            testOptionsExists = true;
        if(methodName.equals("android"))
            androidLineNum = methodCall.getLastLineNumber();

        getTargetDependencies(methodCall);
        super.visitMethodCallExpression(methodCall);
    }

    @Override
    public void visitClosureExpression( ClosureExpression expression ){
        super.visitClosureExpression( expression );
        if(dependencyLineNum != -1){
            columnNum = expression.getLastColumnNumber();
        }
    }

    private void getTargetDependencies(MethodCallExpression methodCall){
        int lineNum = methodCall.getLineNumber();
        if(lineNum>dependencyStartLine && lineNum<dependencyLineNum){
            String dependency = methodCall.getText();
            dependency = dependency.substring(dependency.indexOf('.')+1)
                    .replace("(", " '").replace(')', '\'');

            if(dependency.contains("$")){
                String varName = dependency.substring(dependency.indexOf('$')+1, dependency.lastIndexOf('\''));
                if(variables.containsKey(varName))
                    dependency = dependency.replace("$"+varName, variables.get(varName));
            }
            targetDependencies.add(dependency);

            if(dependency.contains("androidx"))
                useAndroidx = true;
        }
    }

    //if dependencies node exists in the target gradle file
    void updateDependencies(){
        List<String> gradleFileContents = new ArrayList<>();
        try {
            gradleFileContents = Files.readAllLines(Paths.get(targetGradleFile.toURI()));
        } catch (IOException e) {
            e.printStackTrace();
        }

        if(columnNum != -1){
            StringBuilder builder = new StringBuilder( gradleFileContents.get(dependencyLineNum - 1));
            String osName = System.getProperty("os.name");
            for(String dependency : sourceDependencies){
                //FixMe: do not include versions in source and target dependencies while making a comparison
                if(!targetDependencies.contains(dependency) && dependency != null){
                    if(osName.contains("Windows"))
                        builder.insert(columnNum - 2, "\r\n" + dependency + "\r\n" );
                    else
                        builder.insert(columnNum - 2, "\r" + dependency + "\r" );
                }
            }
            gradleFileContents.remove(dependencyLineNum - 1);
            gradleFileContents.add( dependencyLineNum - 1, builder.toString() );
        }else{
            for(String dependency: sourceDependencies){
                if(!targetDependencies.contains(dependency) && dependency != null)
                    gradleFileContents.add( dependencyLineNum - 1, "\t"+dependency);
            }
        }

        updateGradleFile(gradleFileContents);
    }

    //if dependencies node does not exist in target gradle file
    void addDependencies(){
        List<String> gradleFileContents = new ArrayList<>();
        try {
            gradleFileContents = Files.readAllLines( Paths.get( targetGradleFile.toURI() ) );
        } catch (IOException e) {
            e.printStackTrace();
        }

        gradleFileContents.add("");
        gradleFileContents.add("dependencies {");
        for(String dependency : sourceDependencies)
            gradleFileContents.add("\t"+dependency);
        gradleFileContents.add("}");

        updateGradleFile(gradleFileContents);
    }

    void updateGradleFile(List<String> gradleFileContents){
        try {
            Files.write(targetGradleFile.toPath(), gradleFileContents, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    int getDependencyLineNum() {
        return dependencyLineNum;
    }

    boolean getTestOptions(){
        return testOptionsExists;
    }

    int getAndroidLineNum() {
        return androidLineNum;
    }

    boolean getAndroidX() { return useAndroidx; }
}
