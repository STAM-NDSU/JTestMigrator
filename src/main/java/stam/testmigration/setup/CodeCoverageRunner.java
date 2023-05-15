package stam.testmigration.setup;

import org.apache.maven.shared.invoker.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import stam.testmigration.main.TestRunner;
import stam.testmigration.search.CodeSearchResults;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

public class CodeCoverageRunner {
    public double runCodeCoverage(boolean first) {
        double coverageRate;
        String testTargetFile = new CodeSearchResults().getTargetTestFileName();
        if(first && testTargetFile == null){
            return 0;
        }

        if(SetupTargetApp.getBuildType().equals("gradle")){
            runCoverageInGradle(first);
            coverageRate = readCoverageInGradle();
        }else{
            runCoverageInMaven(first);
            coverageRate = readCoverageInMaven();
        }
        // comment this line to debug
        removeAddedCoverageFilter();
        return coverageRate;
    }

    public double readCoverageInGradle() {
        File reportDTD = new File("report.dtd");
        if(!new File(SetupTargetApp.getTargetDir() + File.separator + "build" + File.separator +
                "reports" + File.separator + "jacoco").exists()) {
            //Migration fails, no new code coverage
            System.out.println("Could not calculate code coverage.");
            return -1;
        }
        if(!reportDTD.exists()) {
            System.out.println("File report.dtd does not exist.");
            return -1;
        } else {
            try {
                Files.copy(reportDTD.toPath(),
                        new File(SetupTargetApp.getTargetDir() + File.separator + "build" + File.separator +
                                "reports" + File.separator + "jacoco" + File.separator + "test" + File.separator +
                                "report.dtd").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        File coverageXml = new File(SetupTargetApp.getTargetDir() + File.separator +
                "build" + File.separator + "reports" + File.separator + "jacoco" + File.separator +
                "test" + File.separator + "jacocoTestReport.xml");
        if(coverageXml.exists()){
            return readCoverageInXml(coverageXml);
        }
        return -1;
    }

    private double readCoverageInMaven() {
        File reportDTD = new File("report.dtd");
        if(!new File(SetupTargetApp.getTargetDir() + File.separator + "target" + File.separator +
                "site" + File.separator + "jacoco").exists()) {
            //Migration fails, no new code coverage
            System.out.println("Could not calculate code coverage.");
            return -1;
        }
        if(!reportDTD.exists()) {
            System.out.println("File report.dtd does not exist.");
            return -1;
        } else {
            try {
                Files.copy(reportDTD.toPath(),
                        new File(SetupTargetApp.getTargetDir() + File.separator + "target" + File.separator +
                                "site" + File.separator + "jacoco" + File.separator + "report.dtd").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
        File coverageXml = new File(SetupTargetApp.getTargetDir() + File.separator +
                "target" + File.separator + "site" + File.separator + "jacoco" + File.separator + "jacoco.xml");
        if(coverageXml.exists()){
            return readCoverageInXml(coverageXml);
        }
        return -1;
    }

    private double readCoverageInXml(File coverageXml) {
        String packageName = new SetupTargetApp()
                .getPackageName(new CodeSearchResults()
                        .getTargetFileName(), SetupTargetApp.getTargetDir()).replaceAll("\\.","/");

        CodeSearchResults searchResults = new CodeSearchResults();

        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder;
        Document document = null;
        try {
            dBuilder = dbFactory.newDocumentBuilder();
            document = dBuilder.parse(coverageXml);
        } catch (Exception e) {
            e.printStackTrace();
        }
        document.getDocumentElement().normalize();

        NodeList classNodes = document.getElementsByTagName("class");
        Element classNode = null;
        if(classNodes != null && classNodes.getLength() > 0){
            for (int i = 0; i < classNodes.getLength(); i++) {
                Element el = (Element) classNodes.item(i);
                if(el.hasAttribute("name") && el.getAttribute("name")
                        .equals(packageName + "/" + searchResults.getTargetClassName())){
                    classNode = el;
                }
            }
        }

        ArrayList<Element> methodNodes = new ArrayList<>();
        if(classNode != null){
            NodeList allMethodNodes = classNode.getChildNodes();
            if(allMethodNodes != null && allMethodNodes.getLength() > 0){
                for (int i = 0; i < allMethodNodes.getLength(); i++) {
                    Element el = (Element) allMethodNodes.item(i);
                    if(el.hasAttribute("name") &&
                            el.getAttribute("name").equals(searchResults.getTargetTestMethod())){
                        methodNodes.add(el);
                    }
                }
            }
        }

        ArrayList<Element> counterNodes = new ArrayList<>();
        if(!methodNodes.isEmpty()){
            NodeList allCounterNodes;
            for(Element methodNode: methodNodes){
                allCounterNodes = methodNode.getChildNodes();
                if(allCounterNodes != null && allCounterNodes.getLength() > 0){
                    for (int i = 0; i < allCounterNodes.getLength(); i++) {
                        Element el = (Element) allCounterNodes.item(i);
                        if(el.hasAttribute("type") && el.getAttribute("type").equals("LINE")){
                            counterNodes.add(el);
                        }
                    }
                }
            }
        }

        if(!counterNodes.isEmpty()){
            int missed, covered;
            for(Element counterNode: counterNodes){
                missed = Integer.parseInt(counterNode.getAttribute("missed"));
                covered = Integer.parseInt(counterNode.getAttribute("covered"));
                if(covered !=0 ){
                    return (double) covered/(covered+missed);
                }
            }
        }
        return -1;
    }

    private void runCoverageInGradle(boolean first) {
        CodeSearchResults searchResults = new CodeSearchResults();
        ProcessBuilder processBuilder = new ProcessBuilder();

        Map<String, String> environment = processBuilder.environment();
        String path = environment.get("Path");
        environment.put("Path", path+";"+SetupTargetApp.getGradlePath()+";");

        processBuilder.directory(new File(SetupTargetApp.getTargetDir()));

        GradleCoverageUpdater gradleCoverageUpdater = new GradleCoverageUpdater();
        gradleCoverageUpdater.addPluginAndTasks(searchResults.getTestFileName(), first);

        String testFileName = SetupTargetApp.getTestFileNameInTarget();
        testFileName = testFileName.substring(0, testFileName.indexOf("."));

        String specifyJDK = " -Dorg.gradle.java.home=\""+SetupTargetApp.getJdkRootDir()+"\"";

        String cmd;
        String existingTestName = new CodeSearchResults().getTargetTestFileName();
        if(first){
            cmd = "gradle test --tests " + existingTestName + specifyJDK;
        }else if(existingTestName == null
                || existingTestName.equals("")){
            cmd = "gradle test --tests " + testFileName + specifyJDK;
        }else{
            cmd = "gradle test --tests " + testFileName + existingTestName + specifyJDK;
        }
        new TestRunner().runCommand(cmd, processBuilder);
    }

    private void runCoverageInMaven(boolean first) {
        PomCoverageUpdater pomCoverageUpdater = new PomCoverageUpdater();
        pomCoverageUpdater.updatePomCoverage(first);

        InvocationOutputHandler outputHandler = new InvocationOutputHandler() {
            @Override
            public void consumeLine(String line) throws IOException {
                if(line.contains("Compilation failure")){
                    System.out.println("Compilation failure");
                }
            }
        };

        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(new File(SetupTargetApp.getTargetDir()+File.separator+"pom.xml"));
        request.setGoals(Arrays.asList("clean", "test"));
        request.setErrorHandler(outputHandler);
        request.setOutputHandler(outputHandler);
        request.setJavaHome(new File(SetupTargetApp.getJdkRootDir()));

        Invoker invoker = new DefaultInvoker();
        invoker.setMavenHome(new File(SetupTargetApp.getMavenPath()));
        try {
            InvocationResult result = invoker.execute(request);
        } catch (MavenInvocationException e) {
            e.printStackTrace();
        }
    }

    private void removeAddedCoverageFilter(){
        if(SetupTargetApp.getBuildType().equals("gradle")){
            new GradleCoverageUpdater().removeTestFilter();
        }else if(SetupTargetApp.getBuildType().equals("maven")){
            new PomCoverageUpdater().removeTestFilter();
        }
    }
}
