package stam.testmigration.setup;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import stam.testmigration.main.TestRunner;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class GradleToPomDependencyUpdater {

    void updatePOMFile(){
        TestRunner testRunner = new TestRunner();
        ProcessBuilder processBuilder = new ProcessBuilder();

        processBuilder.directory(new File(SetupTargetApp.getSourceDir()));
        testRunner.setPath(processBuilder, SetupTargetApp.getGradlePath());

        ArrayList<String> sourceDependencies = getSourceDependencies(testRunner, processBuilder);
        ArrayList<String> sourceTestDependencies = getSourceTestDependencies(testRunner, processBuilder);

        GradleUpdater gradleUpdater = new GradleUpdater();
        List<String> imports = gradleUpdater.getImportsFromTest();
        ArrayList<String> srcOtherDependencies = getOtherSourceDependencies(sourceDependencies, sourceTestDependencies);
        ArrayList<String> testImportDependencies = filterImportDependencies(srcOtherDependencies, imports);

        POMUpdater pomUpdater = new POMUpdater();
        File targetPomFile = pomUpdater.getPomFile(SetupTargetApp.getTargetDir());
        File parentTargetPomFile = pomUpdater.getParentPom(SetupTargetApp.getTargetDir());
        ArrayList<DependencyConverter> targetDependencies = pomUpdater.getDependencies(parentTargetPomFile, targetPomFile);

        addDependencies(sourceTestDependencies, testImportDependencies, targetDependencies, targetPomFile, pomUpdater);
    }

    private ArrayList<String> getSourceDependencies(TestRunner testRunner, ProcessBuilder processBuilder){
        testRunner.runCommand("gradle dependencies", processBuilder);
        return getDependencies(processBuilder);
    }

    private ArrayList<String> getSourceTestDependencies(TestRunner testRunner, ProcessBuilder processBuilder){
        testRunner.runCommand("gradle dependencies --configuration testImplementation", processBuilder);
        return getDependencies(processBuilder);
    }

    private ArrayList<String> getDependencies(ProcessBuilder processBuilder){
        ArrayList<String> dependencies = new ArrayList<>();
        try {
            Process process = processBuilder.start();
            BufferedReader stdInput = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = stdInput.readLine()) != null) {
                sanitizeDependencyString(line, dependencies);
            }
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
        return dependencies;
    }

    private void sanitizeDependencyString(String line, ArrayList<String> dependencies){
        if(line.contains("-") && line.contains(".") && line.contains(":")){
            int startIndex = 0;
            for(int i=0; i<line.length(); i++){
                if(Character.isLetter(line.charAt(i))){
                    startIndex = i;
                    break;
                }
            }
            String dependency = line.substring(startIndex);
            if(dependency.contains(" ")){
                dependencies.add(dependency.substring(0, dependency.indexOf(" ")));
            }else{
                dependencies.add(dependency);
            }
        }
    }

    private ArrayList<String> getOtherSourceDependencies(ArrayList<String> sourceDependencies, ArrayList<String> sourceTestDependencies){
        ArrayList<String> otherDependencies = new ArrayList<>();
        for(String dependency: sourceDependencies){
            if(!sourceTestDependencies.contains(dependency)){
                otherDependencies.add(dependency);
            }
        }
        return otherDependencies;
    }

    private ArrayList<String> filterImportDependencies(ArrayList<String> srcOtherDependencies, List<String> imports){
        ArrayList<String> importDependencies = new ArrayList<>();
        for(String dependency: srcOtherDependencies){
            String groupID = dependency.substring(0, dependency.indexOf(":"));
            for(String im: imports){
                if(im.contains(groupID) && !importDependencies.contains(dependency)){
                    importDependencies.add(dependency);
                }
            }
        }
        return importDependencies;
    }

    private void addDependencies(ArrayList<String> testDependencies, ArrayList<String> otherDependencies,
                                 ArrayList<DependencyConverter> targetDependencies, File targetPomFile,
                                 POMUpdater pomUpdater){
        Document document = pomUpdater.getPomJDom(targetPomFile);
        Element rootElement = document.getRootElement();
        Namespace namespace = rootElement.getNamespace();

        ArrayList<DependencyConverter> testDependenciesInTarget = new ArrayList<>();
        ArrayList<DependencyConverter> otherDependenciesInTarget = new ArrayList<>();
        separateTargetDependencies(targetDependencies, testDependenciesInTarget, otherDependenciesInTarget);

        for(String dependency: testDependencies){
            boolean presentInTarget = false;
            for(DependencyConverter converter: testDependenciesInTarget){
                if(converter.isSameDependency(dependency)){
                    presentInTarget = true;
                }
            }
            if(!presentInTarget){
                addDependenciesInTarget(dependency, "test", rootElement, namespace);
            }
        }

        for(String dependency: otherDependencies){
            boolean presentInTarget = false;
            for(DependencyConverter converter: otherDependenciesInTarget){
                if(converter.isSameDependency(dependency)){
                    presentInTarget = true;
                }
            }
            if(!presentInTarget){
                addDependenciesInTarget(dependency, "other", rootElement, namespace);
            }
        }
        pomUpdater.setPomJDom(document, targetPomFile);
    }

    private void separateTargetDependencies(ArrayList<DependencyConverter> targetDependencies,
                                            ArrayList<DependencyConverter> testDependencies,
                                            ArrayList<DependencyConverter> otherDependencies){
        for(DependencyConverter converter: targetDependencies){
            String scope = converter.getScope();
            if(scope != null && scope.equals("test")){
                testDependencies.add(converter);
            }else{
                otherDependencies.add(converter);
            }
        }
    }

    private void addDependenciesInTarget(String dependency, String scope, Element rootElement, Namespace namespace){
        String groupId = dependency.substring(0, dependency.indexOf(":"));
        String artifactId = dependency.substring(dependency.indexOf(":")+1, dependency.lastIndexOf(":"));
        String version = dependency.substring(dependency.lastIndexOf(":")+1);

        DependencyConverter converter = new DependencyConverter(groupId, artifactId, version,
                scope, false, null);
        Element dependenciesElement;
        if(rootElement.getChild("dependencies", namespace) != null){
            dependenciesElement = rootElement.getChild("dependencies", namespace);
            dependenciesElement.addContent(converter.toJDomElement(namespace));
        } else {
            dependenciesElement = new Element("dependencies", namespace);
            dependenciesElement.addContent(converter.toJDomElement(namespace));
            rootElement.addContent(dependenciesElement);
        }
    }
}
