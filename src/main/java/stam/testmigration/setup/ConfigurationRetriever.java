package stam.testmigration.setup;

import org.deeplearning4j.models.embeddings.loader.WordVectorSerializer;
import org.deeplearning4j.models.word2vec.Word2Vec;
import stam.testmigration.search.CodeSearchResults;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class ConfigurationRetriever {

    public static Word2Vec vec;
    public static double thresholdValue;

    public void getConfigurations(){
        Properties prop = new Properties();
        InputStream input;

        try {
            input = new FileInputStream("config.properties");
            prop.load(input);

            CodeSearchResults.setSourceFileName(prop.getProperty("sourceClass")+".java");
            CodeSearchResults.setTestFileName(prop.getProperty("sourceTest")+".java");
            CodeSearchResults.setSourceTestMethod(prop.getProperty("sourceMethod"));
            CodeSearchResults.setTargetFileName(prop.getProperty("targetClass")+".java");
            CodeSearchResults.setTargetTestMethod(prop.getProperty("targetMethod"));

            if(prop.getProperty("targetTest") == null || prop.getProperty("targetTest").equals("")){
                CodeSearchResults.setTargetTestFileName(null);
            }else{
                CodeSearchResults.setTargetTestFileName(prop.getProperty("targetTest")+".java");
            }
            thresholdValue = Double.parseDouble(prop.getProperty("simThreshold"));

            input.close();
        } catch (IOException ex) {
            ex.printStackTrace();
        }

    }

    //get source and target app dirs
    public void getSourceTargetDirs() {

        Properties prop = new Properties();
        InputStream input;
        String word2vecPath = "word_2_vec_path";

        try {
            input = new FileInputStream("config.properties");

            prop.load(input);
            SetupTargetApp.setSourceDir(prop.getProperty("sourcedir"));
            SetupTargetApp.setTargetDir(prop.getProperty("targetdir"));
            SetupTargetApp.setGradlePath(prop.getProperty("gradlepath"));
            SetupTargetApp.setMavenPath(prop.getProperty("mavenpath"));
            SetupTargetApp.setJdkRootDir(prop.getProperty("jdkRootDir"));
            word2vecPath = prop.getProperty("wvPath");

            input.close();

        } catch (IOException ex) {
            ex.printStackTrace();
        }

        if(!new File(SetupTargetApp.getSourceDir()).exists()) {
            System.out.println("Invalid source app directory.");
            System.exit(1);
        }

        if(!new File(SetupTargetApp.getTargetDir()).exists()) {
            System.out.println("Invalid target app directory.");
            System.exit(1);;
        }

        if(!new File(SetupTargetApp.getGradlePath()).exists()) {
            System.out.println("Invalid gradle bin directory.");
            System.exit(1);;
        }

        if(!new File(SetupTargetApp.getMavenPath()).exists()) {
            System.out.println("Invalid maven bin directory.");
            System.exit(1);;
        }

        if(!new File(SetupTargetApp.getJdkRootDir()).exists()) {
            System.out.println("Invalid JDK root directory.");
            System.exit(1);;
        }

        if(!new File(word2vecPath).exists()) {
            System.out.println("Invalid Word2Vec model path.");
            System.exit(1);
        }

        loadWord2Vec(word2vecPath);
    }

    private void loadWord2Vec(String word2vecPath){
        vec = WordVectorSerializer.readWord2VecModel(word2vecPath);
    }
    
}
