package stam.testmigration.main;

import stam.testmigration.setup.ConfigurationRetriever;
import stam.testmigration.setup.SetupTargetApp;

public class TestMigrator {
    public static void main(String[] args) {
        ConfigurationRetriever configRet = new ConfigurationRetriever();
        configRet.getConfigurations();
        configRet.getSourceTargetDirs();
        System.out.println("Migrating Unit Tests...");
        new SetupTargetApp().setupTarget();
        new TestCodeTransformer().transformTest();
    }

}
