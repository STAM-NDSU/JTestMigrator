package stam.testmigration.main;

import org.apache.maven.shared.invoker.*;
import stam.testmigration.search.CodeSearchResults;
import stam.testmigration.setup.GradleTestFilterUpdater;
import stam.testmigration.setup.PomTestFilterUpdater;
import stam.testmigration.setup.SetupTargetApp;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class TestRunner {

    boolean firstTestRun = true;
    private final int[] errorCode = new int[]{0, 1};

    int[] runTest() {
        ProcessBuilder processBuilder = new ProcessBuilder();
        String buildType = SetupTargetApp.getBuildType();
        if(buildType.equals("gradle")){
            setTargetDirForCmdLine(processBuilder);
            runTestInGradle(new CodeSearchResults(), processBuilder);
            checkErrorMessage(processBuilder);
        }else if(buildType.equals("maven")){
            runTestInMaven();
        }
        return errorCode;
    }

    private void runTestInMaven(){
        //run single migrated test class
        if(firstTestRun){
            firstTestRun = false;
            new PomTestFilterUpdater().addTestFilter();
        }

        InvocationOutputHandler outputHandler = new InvocationOutputHandler() {
            @Override
            public void consumeLine(String line) throws IOException {
                if(line.contains("Compilation failure") || line.contains("Fatal error compiling")){
                    errorCode[0] = 1;
                }
            }
        };
        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(new File(SetupTargetApp.getTargetDir()+File.separator+"pom.xml"));
        request.setGoals(Arrays.asList("test"));
        request.setErrorHandler(outputHandler);
        request.setOutputHandler(outputHandler);
        request.setJavaHome(new File(SetupTargetApp.getJdkRootDir()));

        Invoker invoker = new DefaultInvoker();
        invoker.setMavenHome(new File(SetupTargetApp.getMavenPath()));
        InvocationResult result = null;
        try {
            result = invoker.execute(request);
        } catch (MavenInvocationException e) {
            e.printStackTrace();
        }
        errorCode[1] = result.getExitCode();
        //String specifyJDK = " -Dmaven.compiler.fork=true -Dmaven.compiler.executable=\""+SetupTargetApp.jdkRootDir+"\"";
    }

    private void runTestInGradle(CodeSearchResults searchResults, ProcessBuilder processBuilder){
        setPath(processBuilder, SetupTargetApp.getGradlePath());

        String testFileName = getTestFileName(searchResults);
        String testVariantTask = getTestTask(processBuilder);
        String specifyJDK = " -Dorg.gradle.java.home=\""+SetupTargetApp.getJdkRootDir()+"\"";
        String cmd;
        if(testVariantTask != null) {
            List<String> moduleNames = getModuleNames();
            if(moduleNames.isEmpty())
                cmd = "gradle " + testVariantTask + " --tests " + testFileName + specifyJDK;
            else if(moduleNames.contains("app"))
                cmd = "gradle :app:" + testVariantTask + " --tests " + testFileName + specifyJDK;
            else
                cmd = addTestFilterInGradle(testFileName, specifyJDK);
        }else{
            cmd = addTestFilterInGradle(testFileName, specifyJDK);
        }
        runCommand(cmd, processBuilder);
    }

     //Command "gradle test --tests testFileName" is not working. Only test variant works.
    private String addTestFilterInGradle(String testFileName, String specifyJDK){
        if(firstTestRun){
            firstTestRun = false;
            new GradleTestFilterUpdater().addTestFilter(testFileName);
        }
        return  "gradle test "+specifyJDK;
    }

    private List<String> getModuleNames(){
        List<String> moduleNames = new ArrayList<>();
        SetupTargetApp setupTargetApp = new SetupTargetApp();
        String path = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getTargetDir()), "settings.gradle");
        if(path != null){
            try {
                Files.readAllLines(new File(path).toPath()).forEach(line -> {
                    if(line.startsWith("include")){
                        for(String module:line.split(" ")){
                            if(module.contains(":") && module.contains("'")){
                                moduleNames.add(module.substring(module.indexOf(':')+1, module.lastIndexOf('\'')));
                            }else if(module.contains(":") && module.contains("\"")){
                                moduleNames.add(module.substring(module.indexOf(':')+1, module.lastIndexOf('\"')));
                            }
                        }
                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return moduleNames;
    }

    private void setTargetDirForCmdLine(ProcessBuilder processBuilder){
        processBuilder.directory(new File(SetupTargetApp.getTargetDir()));
    }

    public void setPath(ProcessBuilder processBuilder, String classPath){
        Map<String, String> environment = processBuilder.environment();
        String path = environment.get("Path");
        environment.put("Path", path+";"+classPath+";");
    }

    private String getTestFileName(CodeSearchResults searchResults){
        String testFileName = SetupTargetApp.getTestFileNameInTarget();
        return testFileName.substring(0, testFileName.indexOf("."));
    }

    private String getTestTask(ProcessBuilder processBuilder){
        String taskName = null;
        String cmd = "gradle tasks --group=\"verification\"";
        runCommand(cmd, processBuilder);
        try{
            Process process = processBuilder.start();
            BufferedReader stdOutput = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = stdOutput.readLine()) != null) {
                if(line.startsWith("test") && line.contains("UnitTest"))
                    taskName = line.substring(0, line.indexOf(" "));
            }
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
        return taskName;
    }

    public void runCommand(String cmd, ProcessBuilder processBuilder){
        String osName = System.getProperty("os.name");
        if(osName.contains("Windows"))
            processBuilder.command("cmd.exe", "/c", cmd);
        else if(osName.contains("Linux") || osName.contains("Mac"))
            processBuilder.command("bash", "-c", cmd);
        else
            System.out.println("Command does not work on "+osName+". Please use Windows or Linux");
    }

    private void checkErrorMessage(ProcessBuilder processBuilder){
        //1st value represents compile error and 2nd value represents test failure
        try {
            Process process = processBuilder.start();
            BufferedReader stdError = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()));
            String line;
            while ((line = stdError.readLine()) != null) {
                System.out.println(line);
                if(line.contains("error: cannot find symbol"))
                    errorCode[0] = 1;
            }
            errorCode[1] = process.waitFor();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

}
