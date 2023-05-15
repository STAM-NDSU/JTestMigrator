package stam.testmigration.setup;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.builder.AstBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class GradleUpdater {

    SetupTargetApp targetApp = new SetupTargetApp();

    void updateTargetGradle(){

        File sourceGradleFile = getGradleFile(SetupTargetApp.getSourceDir(), targetApp);
        File targetGradleFile = getGradleFile(SetupTargetApp.getTargetDir(), targetApp);

        List<String> dependencies = getDependencies(sourceGradleFile);
        addTestDependencies(targetGradleFile, dependencies);
        disableUnitTestBinaryResources();
    }

    File getGradleFile(String dir, SetupTargetApp targetApp){
        String appDir = targetApp.findFileOrDir(new File(dir), "src");
        appDir = appDir.substring(0, appDir.lastIndexOf("src"));

        String gradlePath = null;
        if(appDir != null)
            gradlePath = appDir+"build.gradle";
        else{
            System.out.println("\"src\" dir does not exist in "+dir);
            System.exit(1);
        }
        File gradleFile = new File(gradlePath);
        if(!gradleFile.exists()){
            System.out.println("build.gradle file does not exist in "+appDir);
            System.exit(1);
        }
        return gradleFile;
    }

    //get all test dependencies from source app
    List<String> getDependencies(File sourceGradle){
        List<String> dependencies = new ArrayList<>();
        List<ASTNode> nodes = getNodes(sourceGradle);
        TestDependencyFinder testDependencyFinder = new TestDependencyFinder();
        nodes.forEach(node -> node.visit(testDependencyFinder));
        dependencies.addAll(testDependencyFinder.testDependencies);
        dependencies.addAll(getOtherDependencies(testDependencyFinder));
        return dependencies;
    }

    //get libraries' dependencies used in the test class
    private List<String> getOtherDependencies(TestDependencyFinder testDependencyFinder){
        List<String> dependencies = new ArrayList<>();
        List<String> imports = getImportsFromTest();
        for(String dependency: testDependencyFinder.otherDependencies){
            String groupID = dependency.substring(dependency.indexOf(" ")+2, dependency.indexOf(":"));
            imports.forEach(im -> {
                if(im.contains(groupID))
                    dependencies.add(dependency);
            });
        }
        return dependencies;
    }

    List<String> getImportsFromTest(){
        List<String> imports = new ArrayList<>();
        CompilationUnit cu = getTestCompilation();
        cu.accept(new VoidVisitorAdapter<>() {
            @Override
            public void visit(ImportDeclaration node, Object arg){
                super.visit(node, arg);
                imports.add(node.getNameAsString());
            }
        }, null);
        return imports;
    }

    private CompilationUnit getTestCompilation(){
        SetupTargetApp setupTargetApp = new SetupTargetApp();
        String testFileName = SetupTargetApp.getTestFileNameInTarget();
        String path = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getTargetDir()), testFileName);
        File testFile = new File(path);
        return SetupTargetApp.getCompilationUnit(testFile);
    }

    //add or update test dependencies in the target app
    void addTestDependencies(File targetGradle, List<String> dependencies){
        TestDependencyUpdater testDependencyUpdater = new TestDependencyUpdater(targetGradle, dependencies);

        List<ASTNode> nodes = getNodes(targetGradle);
        nodes.forEach(node -> node.visit(testDependencyUpdater));

        if(testDependencyUpdater.getDependencyLineNum() != -1)
            testDependencyUpdater.updateDependencies();
        else testDependencyUpdater.addDependencies();

        if(!testDependencyUpdater.getTestOptions() && testDependencyUpdater.getAndroidLineNum()>0 && isAndroid(new File(SetupTargetApp.getTargetDir())))
            addTestOptions(targetGradle, testDependencyUpdater.getAndroidLineNum(), testDependencyUpdater);

        if(testDependencyUpdater.getAndroidX() && isAndroid(new File(SetupTargetApp.getTargetDir())))
            enableAndroidX();
    }

    //enable androidx
    private void enableAndroidX(){
        modifyGradleProperties("android.useAndroidX=true");
    }

    void disableUnitTestBinaryResources(){
        modifyGradleProperties("android.enableUnitTestBinaryResources=false");
    }

    private void modifyGradleProperties(String property){
        String filePath = targetApp.findFileOrDir(new File(SetupTargetApp.getTargetDir()), "gradle.properties");
        if(filePath != null){
            File gradleProperties = new File(filePath);
            try {
                List<String> fileContents = Files.readAllLines(Paths.get(gradleProperties.toURI()));
                if(!fileContents.contains(property)){
                    fileContents.add(property);
                    Files.write(gradleProperties.toPath(), fileContents, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }else{
            File gradleProperties = new File(SetupTargetApp.getTargetDir()+File.separator+"gradle.properties");
            try {
                Files.createFile(gradleProperties.toPath());
                Files.writeString(gradleProperties.toPath(), property);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    //add testOptions node if not present in the target gradle file
    private void addTestOptions(File targetGradleFile, int androidLineNum, TestDependencyUpdater testDependencyUpdater){
        List<String> gradleFileContents = new ArrayList<>();
        try {
            gradleFileContents = Files.readAllLines( Paths.get( targetGradleFile.toURI() ) );
        } catch (IOException e) {
            e.printStackTrace();
        }

        gradleFileContents.add(androidLineNum-1, "");
        gradleFileContents.add(androidLineNum, "\ttestOptions {");
        gradleFileContents.add(androidLineNum+1, "\t\tunitTests.returnDefaultValues = true");
        gradleFileContents.add(androidLineNum+2, "\t\tunitTests.includeAndroidResources = true");
        gradleFileContents.add(androidLineNum+3, "\t}");

        testDependencyUpdater.updateGradleFile(gradleFileContents);
    }

    List<ASTNode> getNodes(File file){
        List<ASTNode> nodes = new ArrayList<>();
        try {
            nodes = new AstBuilder().buildFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            e.printStackTrace();
        }
        return nodes;
    }

    private boolean isAndroid(File dir){
        String path = targetApp.findFileOrDir(dir, "AndroidManifest.xml");
        if(path != null){
            return true;
        }
        return false;
    }
}
