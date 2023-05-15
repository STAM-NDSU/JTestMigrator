package stam.testmigration.main;

import stam.testmigration.setup.*;
import stam.testmigration.setup.ConfigCreator;
import stam.testmigration.setup.ConfigurationRetriever;
import stam.testmigration.setup.SetupTargetApp;

public class TestMigrator {
    public static void main(String[] args) {

        ConfigCreator configCreator = new ConfigCreator();
        configCreator.createConfigFile();

        ConfigurationRetriever configurationRetriever = new ConfigurationRetriever();
        configurationRetriever.getConfigurations();
        configurationRetriever.getSourceTargetDirs();

        SetupTargetApp setupTargetApp = new SetupTargetApp();
        setupTargetApp.setupTarget();

        TestModifier testModifier = new TestModifier();
        testModifier.modifyTest();
    }

}
