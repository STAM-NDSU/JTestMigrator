package stam.testmigration.setup;

import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestDependencyFinder extends CodeVisitorSupport {
    List<String> testDependencies = new ArrayList<>();
    List<String> otherDependencies = new ArrayList<>();
    private Map<String, String> variables = new HashMap<>();

    //store all variables and their values
    @Override
    public void visitBinaryExpression(BinaryExpression binaryExpression){
        super.visitBinaryExpression(binaryExpression);
        variables.put(binaryExpression.getLeftExpression().getText(), binaryExpression.getRightExpression().getText());
    }

    //get all unit test dependencies from source app
    @Override
    public void visitMethodCallExpression(MethodCallExpression methodCall){
        super.visitMethodCallExpression(methodCall);
        String methodName = methodCall.getMethodAsString();
        if(methodName.equals("testImplementation") || methodName.equals("androidTestImplementation"))
            testDependencies.add(getDependency(methodCall));
        else if(methodName.toLowerCase().contains("implementation"))
            otherDependencies.add(getDependency(methodCall));
    }

    private String getDependency(MethodCallExpression methodCall){
        String dependency = methodCall.getText();

        if(dependency.contains("androidTestImplementation"))
            dependency = filterDependency(dependency);

        if(dependency != null){
            dependency = dependency.substring(dependency.indexOf('.')+1)
                    .replace("(", " '").replace(')', '\'');
            if(dependency.contains("$")){
                String varName = dependency.substring(dependency.indexOf('$')+1, dependency.lastIndexOf('\''));
                if(variables.containsKey(varName))
                    dependency = dependency.replace("$"+varName, variables.get(varName));
            }

        }
        return dependency;
    }

    //need only testImplementation dependencies for unit testing
    private String filterDependency(String dependency){
        //need to add more filters as we progress
        if(!dependency.contains("espresso"))
            return dependency.replace("androidTestImplementation", "testImplementation");
        return null;
    }

}
