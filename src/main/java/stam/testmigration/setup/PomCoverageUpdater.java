package stam.testmigration.setup;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import stam.testmigration.search.CodeSearchResults;

import java.io.File;
import java.util.List;

public class PomCoverageUpdater {
    static Document originalModuleDocument = null;
    CodeSearchResults searchResults = new CodeSearchResults();

    public void updatePomCoverage(boolean first) {
        POMUpdater pomUpdater = new POMUpdater();

        File moduleFile = new PomTestFilterUpdater().getPomFile(SetupTargetApp.getTargetDir());
        Document moduleDocument = pomUpdater.getPomJDom(moduleFile);
        originalModuleDocument =  pomUpdater.getPomJDom(moduleFile);

        removeOriginalPlugin(moduleDocument, moduleFile, first);
        addPlugin(moduleDocument, moduleFile, first);
    }

    public void addPlugin(Document moduleDocument, File targetPomFile, boolean first) {
        POMUpdater pomUpdater = new POMUpdater();
        Element rootElement = moduleDocument.getRootElement();
        Namespace namespace = rootElement.getNamespace();

        Element buildElement;
        if(rootElement.getChild("build", namespace) != null){
            buildElement = rootElement.getChild("build", namespace);
        }else{
            buildElement = new Element("build", namespace);
            rootElement.addContent(buildElement);
        }

        Element jacocoPlugin = createJacocoPlugin(namespace);
        Element surefirePlugin = createSurefirePlugin(namespace, first);

        if(buildElement.getChild("plugins", namespace) != null){
            Element pluginsElement = buildElement.getChild("plugins", namespace);
            if(first){
                Element compilerPlugin = createCompilerPlugin(namespace);
                pluginsElement.addContent(compilerPlugin);
            }
            pluginsElement.addContent(jacocoPlugin);
            pluginsElement.addContent(surefirePlugin);
        }else{
            Element pluginsElement = new Element("plugins", namespace);
            if(first){
                Element compilerPlugin = createCompilerPlugin(namespace);
                pluginsElement.addContent(compilerPlugin);
            }
            pluginsElement.addContent(jacocoPlugin);
            pluginsElement.addContent(surefirePlugin);
            buildElement.addContent(pluginsElement);
        }

        pomUpdater.setPomJDom(moduleDocument, targetPomFile);
    }

    private Element createCompilerPlugin(Namespace namespace) {
        Element compilerElement = new Element("plugin", namespace);
        compilerElement.addContent(new Element("groupId", namespace).setText("org.apache.maven.plugins"));
        compilerElement.addContent(new Element("artifactId", namespace).setText("maven-compiler-plugin"));

        Element executions = new Element("executions", namespace);
        Element execution = new Element("execution", namespace);
        execution.addContent(new Element("id", namespace).setText("default-testCompile"));
        execution.addContent(new Element("phase", namespace).setText("test-compile"));

        Element configuration = new Element("configuration", namespace);
        Element testExclude = new Element("testExcludes", namespace);

        String testFileName = "**/" + SetupTargetApp.getTestFileNameInTarget();
        testExclude.addContent(new Element("exclude", namespace).setText(testFileName));

        configuration.addContent(testExclude);

        Element goals = new Element("goals", namespace);
        goals.addContent(new Element("goal", namespace).setText("testCompile"));

        execution.addContent(configuration);
        execution.addContent(goals);
        executions.addContent(execution);
        compilerElement.addContent(executions);
        return compilerElement;
    }


    private Element createJacocoPlugin(Namespace namespace) {
        Element jacocoElement = new Element("plugin", namespace);
        jacocoElement.addContent(new Element("groupId", namespace).setText("org.jacoco"));
        jacocoElement.addContent(new Element("artifactId", namespace).setText("jacoco-maven-plugin"));
        jacocoElement.addContent(new Element("version", namespace).setText("0.8.5"));


        Element execution1 = new Element("execution", namespace);
        Element goals1 = new Element("goals", namespace);
        goals1.addContent(new Element("goal", namespace).setText("prepare-agent"));
        execution1.addContent(goals1);

        Element execution2 = new Element("execution", namespace);
        execution2.addContent(new Element("id", namespace).setText("report"));
        execution2.addContent(new Element("phase", namespace).setText("test"));
        Element goals2 = new Element("goals", namespace);
        goals2.addContent(new Element("goal", namespace).setText("report"));
        execution2.addContent(goals2);

        Element configuration = new Element("configuration", namespace);
        Element includes = new Element("includes", namespace);
        includes.addContent(new Element("include", namespace).setText("**/" + searchResults.getTargetClassName() + ".class"));
        configuration.addContent(includes);
        execution2.addContent(configuration);

        Element executions = new Element("executions", namespace);
        executions.addContent(execution1);
        executions.addContent(execution2);
        jacocoElement.addContent(executions);

        return jacocoElement;
    }

    private Element createSurefirePlugin(Namespace namespace, boolean first) {
        Element surefireElement = new Element("plugin", namespace);
        surefireElement.addContent(new Element("groupId", namespace).setText("org.apache.maven.plugins"));
        surefireElement.addContent(new Element("version", namespace).setText("3.0.0-M5"));
        surefireElement.addContent(new Element("artifactId", namespace).setText("maven-surefire-plugin"));

        Element configurationElement = new Element("configuration", namespace);
        Element includesElement = new Element("includes", namespace);

        configurationElement.addContent(new Element("testFailureIgnore", namespace).setText("true"));

        if(first){
            String packageName = new SetupTargetApp()
                    .getPackageName(new CodeSearchResults()
                            .getTargetFileName(), SetupTargetApp.getTargetDir());

            includesElement
                    .addContent(new Element("include", namespace)
                            .setText(packageName + "." + new CodeSearchResults().getTargetTestFileName()));
        }else if(new CodeSearchResults().getTargetTestFileName() == null
                || new CodeSearchResults().getTargetTestFileName().equals("")){
            String packageName = new SetupTargetApp()
                    .getPackageName(new CodeSearchResults().getTargetFileName(), SetupTargetApp.getTargetDir());

            includesElement
                    .addContent(new Element("include", namespace)
                            .setText(packageName + "." + SetupTargetApp.getTestFileNameInTarget()));
        }else{
            String packageName1 = new SetupTargetApp()
                    .getPackageName(new CodeSearchResults().getTargetTestFileName(), SetupTargetApp.getTargetDir());
            String packageName2 = new SetupTargetApp()
                    .getPackageName(new CodeSearchResults().getTargetFileName(), SetupTargetApp.getTargetDir());

            includesElement
                    .addContent(new Element("include", namespace)
                            .setText(packageName1 + "." + new CodeSearchResults().getTargetTestFileName()));
            includesElement
                    .addContent(new Element("include", namespace)
                            .setText(packageName2 + "." + SetupTargetApp.getTestFileNameInTarget()));
        }

        configurationElement.addContent(includesElement);
        configurationElement.addContent(new Element("argLine", namespace).setText("${argLine}"));
        surefireElement.addContent(configurationElement);
        return surefireElement;
    }

    private void removeOriginalPlugin(Document document, File targetPomFile, boolean first) {
        POMUpdater pomUpdater = new POMUpdater();
        Element rootElement = document.getRootElement();
        Namespace namespace = rootElement.getNamespace();

        if(rootElement.getChild("build", namespace) == null ||
                rootElement.getChild("build", namespace).getChild("plugins", namespace) == null
        ){
            return;
        }

        Element pluginsElement = rootElement.getChild("build", namespace).getChild("plugins", namespace);
        List<Element> plugins = pluginsElement.getChildren();

        Element jacocoPlugin = null;
        Element surefirePlugin = null;
        Element compilerPlugin = null;
        for(Element plugin: plugins){
            if(plugin.getChild("artifactId", namespace) != null
                    && plugin.getChild("artifactId", namespace).getText().equals("jacoco-maven-plugin")){
                jacocoPlugin = plugin;
            }
            if(plugin.getChild("artifactId", namespace) != null
                    && plugin.getChild("artifactId", namespace).getText().equals("maven-surefire-plugin")){
                surefirePlugin = plugin;
            }
            if(plugin.getChild("artifactId", namespace) != null
                    && plugin.getChild("artifactId", namespace).getText().equals("maven-compiler-plugin")){
                compilerPlugin = plugin;
            }
        }
        if(jacocoPlugin != null){
            pluginsElement.removeContent(jacocoPlugin);
        }
        if(surefirePlugin != null){
            pluginsElement.removeContent(surefirePlugin);
        }
        if(compilerPlugin != null && first){
            pluginsElement.removeContent(compilerPlugin);
        }

        pomUpdater.setPomJDom(document, targetPomFile);
    }

    public void removeTestFilter(){
        POMUpdater pomUpdater = new POMUpdater();
        File moduleFile = new PomTestFilterUpdater().getPomFile(SetupTargetApp.getTargetDir());
        pomUpdater.setPomJDom(originalModuleDocument, moduleFile);
    }
}
