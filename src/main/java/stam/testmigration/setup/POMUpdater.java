package stam.testmigration.setup;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.maven.model.Model;

public class POMUpdater {

    void updatePOMFile(){
        File sourcePomFile = getPomFile(SetupTargetApp.getSourceDir());
        File parentSourcePomFile = getParentPom(SetupTargetApp.getSourceDir());
        File parentTargetPomFile = getParentPom(SetupTargetApp.getTargetDir());
        File targetPomFile = getPomFile(SetupTargetApp.getTargetDir());

        ArrayList<DependencyConverter> sourceDependencyConverters = getDependencies(parentSourcePomFile, sourcePomFile);
        ArrayList<DependencyConverter> targetDependencyConverters = getDependencies(parentTargetPomFile, targetPomFile);

        ArrayList<DependencyConverter> testDependencyConverters =
                getTestDependencyConverters(sourceDependencyConverters, targetDependencyConverters);

        List<String> imports = getImportsFromTest();
        ArrayList<DependencyConverter> otherDependencyConverters =
                getOtherDependencyConverters(sourceDependencyConverters, targetDependencyConverters, imports);

        ArrayList<DependencyConverter> dependencyConvertersRequired = new ArrayList<>();
        dependencyConvertersRequired.addAll(testDependencyConverters);
        dependencyConvertersRequired.addAll(otherDependencyConverters);

        addDependenciesInTarget(dependencyConvertersRequired, targetPomFile);
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

    private void addDependenciesInTarget(ArrayList<DependencyConverter> dependencyConvertersRequired, File targetPomFile){
        Document document = getPomJDom(targetPomFile);
        Element rootElement = document.getRootElement();
        Namespace namespace = rootElement.getNamespace();

        Element dependenciesElement = rootElement.getChild("dependencies", namespace);
        Element dependencyManagementElement = rootElement.getChild("dependencyManagement", namespace);
        Element managementDependenciesElement = null;
        if(dependencyManagementElement != null){
          managementDependenciesElement = dependencyManagementElement.getChild("dependencies", namespace);
        }

        if(dependenciesElement != null || managementDependenciesElement != null){
            for(DependencyConverter converter: dependencyConvertersRequired){
                if(converter.getManagement()){
                    addDependencyElementInManagement(converter, rootElement, namespace);
                }else{
                    if(dependenciesElement != null) {
                        dependenciesElement.addContent(converter.toJDomElement(namespace));
                    } else {
                        managementDependenciesElement.addContent(converter.toJDomElement(namespace));
                    }
                }
            }
        }else{
            dependenciesElement = new Element("dependencies", namespace);
            for(DependencyConverter converter: dependencyConvertersRequired){
                if(converter.getManagement()){
                    addDependencyElementInManagement(converter, rootElement, namespace);
                }else{
                    dependenciesElement.addContent(converter.toJDomElement(namespace));
                }
            }
            rootElement.addContent(dependenciesElement);
        }

        setPomJDom(document, targetPomFile);
    }

    private void addDependencyElementInManagement(DependencyConverter converter,
                                                  Element rootElement, Namespace namespace){
        if(rootElement.getChild("dependencyManagement", namespace) != null){
            Element dependencies = rootElement.getChild("dependencyManagement", namespace)
                    .getChild("dependencies", namespace);
            dependencies.addContent(converter.toJDomElement(namespace));
        }else{
            Element management = new Element("dependencyManagement", namespace);
            Element dependencies = new Element("dependencies", namespace);

            dependencies.addContent(converter.toJDomElement(namespace));
            management.addContent(dependencies);
            rootElement.addContent(management);
        }
    }

    private ArrayList<DependencyConverter> getOtherDependencyConverters(ArrayList<DependencyConverter> sourceDependencies,
                                                                        ArrayList<DependencyConverter> targetDependencies,
                                                                        List<String> imports){


        ArrayList<DependencyConverter> sConverters = filterDependencyConverters(sourceDependencies);
        ArrayList<DependencyConverter> dependencyConverters = new ArrayList<>();
        for(DependencyConverter converter: getDependencyConvertersNotInTarget(sConverters, targetDependencies)){
            String groupID = converter.getGroupId();
            imports.forEach(im -> {
                if(im.contains(groupID) && !dependencyConverters.contains(converter))
                    dependencyConverters.add(converter);
            });
        }
        return dependencyConverters;
    }

    ArrayList<DependencyConverter> filterDependencyConverters(ArrayList<DependencyConverter> converters){
        ArrayList<DependencyConverter> otherDependencies = new ArrayList<>();
        for(DependencyConverter converter: converters){
            if(converter.getScope() == null || !converter.getScope().equals("test")){
                otherDependencies.add(converter);
            }
        }
        return otherDependencies;
    }

    private ArrayList<DependencyConverter> getTestDependencyConverters(ArrayList<DependencyConverter> sourceDependencies,
                                                                     ArrayList<DependencyConverter> targetDependencies){
        ArrayList<DependencyConverter> sourceTestDependencyConverters = filterTestDependencyConverters(sourceDependencies);

        return getDependencyConvertersNotInTarget(sourceTestDependencyConverters, targetDependencies);
    }

    private ArrayList<DependencyConverter> getDependencyConvertersNotInTarget(ArrayList<DependencyConverter> sourceTestDependencyConverters,
                                                                             ArrayList<DependencyConverter> targetTestDependencyConverters){
        ArrayList<DependencyConverter> testDependencyConverters = new ArrayList<>();
        for(DependencyConverter converter: sourceTestDependencyConverters){
            if(!converter.isInTargetConverters(targetTestDependencyConverters)){
                testDependencyConverters.add(converter);
            }
        }
        return testDependencyConverters;
    }

    ArrayList<DependencyConverter> filterTestDependencyConverters(ArrayList<DependencyConverter> dependencies){
        ArrayList<DependencyConverter> converters = new ArrayList<>();
        for(DependencyConverter converter: dependencies){
            if(converter.getScope() != null && converter.getScope().equals("test")){
                converters.add(converter);
            }
        }
        return converters;
    }

    private void addNonManagementDependencies(ArrayList<DependencyConverter> converters, Model pomModel){
        List<Dependency> parentDependencies = pomModel.getDependencies();
        if(parentDependencies != null){
            parentDependencies.forEach(dependency -> {
                DependencyConverter converter = new DependencyConverter(dependency.getGroupId(),
                        dependency.getArtifactId(), dependency.getVersion(), dependency.getScope(),
                        false, dependency.getClassifier());
                converter.resolvePomVersion(pomModel);
                converters.add(converter);
            });
        }
    }

    private void addManagementDependencies(ArrayList<DependencyConverter> converters, Model pomModel){
        DependencyManagement moduleManagement = pomModel.getDependencyManagement();
        if(moduleManagement != null){
            moduleManagement.getDependencies().forEach(dependency -> {
                DependencyConverter converter = new DependencyConverter(dependency.getGroupId(),
                        dependency.getArtifactId(), dependency.getVersion(), dependency.getScope(),
                        true, dependency.getClassifier());
                converter.resolvePomVersion(pomModel);
                converters.add(converter);
            });
        }
    }

    ArrayList<DependencyConverter> getDependencies(File parentPom, File modulePom){
        Model parentPomModel = getPomModel(parentPom);
        Model modulePomModel = getPomModel(modulePom);

        ArrayList<DependencyConverter> dependencies = new ArrayList<>();
        if(parentPomModel != null){
            addNonManagementDependencies(dependencies, parentPomModel);
            addManagementDependencies(dependencies, parentPomModel);
        }

        addNonManagementDependencies(dependencies, modulePomModel);
        addManagementDependencies(dependencies, modulePomModel);

        return dependencies;
    }

    void setPomJDom(Document document, File pomFile){
        XMLOutputter xmlOutputter = new XMLOutputter(Format.getPrettyFormat());
        if(document != null){
            try {
                xmlOutputter.output(document, new FileWriter(pomFile));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    Document getPomJDom(File pomFile){
        SAXBuilder builder = new SAXBuilder();
        Document document = null;
        if(pomFile != null){
            try {
                document = builder.build(pomFile);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return document;
    }

    Model getPomModel(File pomFile){
        MavenXpp3Reader reader = new MavenXpp3Reader();
        Model model = null;
        if(pomFile != null){
            try {
                model = reader.read(new FileReader(pomFile));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return model;
    }

    File getPomFile(String projectRootDir){
        File pomFile = new File(projectRootDir+File.separator+"pom.xml");
        if(!pomFile.exists()){
            System.out.println("pom.xml file does not exist in "+projectRootDir);
            System.exit(0);
        }
        return pomFile;
    }

    File getParentPom(String moduleRootDir){
        File moduleRootFile = new File(moduleRootDir);
        File parentPomFile = null;
        if(moduleRootFile.getParentFile().exists()){
            File projectRootDir = moduleRootFile.getParentFile();
            for(File file: Objects.requireNonNull(projectRootDir.listFiles())){
                if(file.getName().equals("pom.xml")){
                    parentPomFile = file;
                }
            }
        }
        return parentPomFile;
    }
}
