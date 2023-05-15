package stam.testmigration.setup;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class PomToGradleDependencyUpdater {

    private final POMUpdater pomUpdater = new POMUpdater();
    private final SetupTargetApp setupTargetApp = new SetupTargetApp();
    private final GradleUpdater gradleUpdater = new GradleUpdater();

    void updateGradle(){
        ArrayList<DependencyConverter> sourceDependencies = getSourceDependencyConverters();
        ArrayList<DependencyConverter> srcTestDependencies = pomUpdater.filterTestDependencyConverters(sourceDependencies);
        ArrayList<String> srcTestDependenciesGF = convertToGradleFormat(srcTestDependencies, "testImplementation");

        List<String> imports = pomUpdater.getImportsFromTest();

        ArrayList<DependencyConverter> srcOtherDependencies = pomUpdater.filterDependencyConverters(sourceDependencies);
        ArrayList<DependencyConverter> testImportDependencies = filterImportDependencyConverters(srcOtherDependencies, imports);
        ArrayList<String> srcOtherDependenciesGF = convertToGradleFormat(testImportDependencies, "implementation");

        ArrayList<String> dependencies = new ArrayList<>();
        dependencies.addAll(srcTestDependenciesGF);
        dependencies.addAll(srcOtherDependenciesGF);

        File targetGradleFile = gradleUpdater.getGradleFile(SetupTargetApp.getTargetDir(), setupTargetApp);
        gradleUpdater.addTestDependencies(targetGradleFile, dependencies);
    }

    private ArrayList<DependencyConverter> filterImportDependencyConverters(ArrayList<DependencyConverter> converters,
                                                                         List<String> imports){
        ArrayList<DependencyConverter> importDependencies = new ArrayList<>();
        for(DependencyConverter converter: converters){
            String groupId = converter.getGroupId();
            for(String im: imports){
                if(groupId != null && im.contains(groupId)){
                    importDependencies.add(converter);
                }
            }
        }
        return importDependencies;
    }

    private ArrayList<DependencyConverter> getSourceDependencyConverters(){
        File sourcePomFile = pomUpdater.getPomFile(SetupTargetApp.getSourceDir());
        File parentSourcePomFile = pomUpdater.getParentPom(SetupTargetApp.getSourceDir());
        return pomUpdater.getDependencies(parentSourcePomFile, sourcePomFile);
    }

    private ArrayList<String> convertToGradleFormat(ArrayList<DependencyConverter> converters, String scope){
        ArrayList<String> sourceDependencies = new ArrayList<>();
        for(DependencyConverter converter: converters){
            sourceDependencies.add(converter.toGradleFormat(scope));
        }
        return  sourceDependencies;
    }

}
