package stam.testmigration.main;

import stam.testmigration.setup.ConfigCreator;
import stam.testmigration.setup.ConfigurationRetriever;
import stam.testmigration.setup.SetupTargetApp;

public class TestMigrator {
    public static void main(String[] args) {

        new ConfigCreator().createConfigFile();

        ConfigurationRetriever configRet = new ConfigurationRetriever();
        configRet.getConfigurations();
        configRet.getSourceTargetDirs();

        new SetupTargetApp().setupTarget();
        new TestCodeTransformer().modifyTest();
    }

}
