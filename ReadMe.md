# JTestMigrator
An automated tool to migrate JUnit tests.

#### **How does it work?**

* Following environment variables need to be set in the `\JTestMigrator\config.properties` file:
    1) `jdkRootDir` - jdk path
    2) `gradlepath` - gradle path (only for gradle projects)
    3) `mavenpath` - maven path (only for maven projects)


* After setting the environment variables, following inputs related to the source and target applications need to be passed through the `\JTestMigrator\config.properties` file:

    _Target Application:_
    1) `targetdir` - the path of the root directory (parent of `src` directory) of the target application
    2) `targetMethod` - the name of the method that needs to be tested in the target application.
    3) `targetClass` - the name of the class that declares the target method

    _Source Application:_
    1) `sourcedir` - the path of the root directory (parent of `src` directory) of the source application.
    2) `sourceMethod` - the name of the method that performs the same function as the target method 
    3) `sourceClass` - the name of the class that declares the source method 
    4) `sourceTest` - the name of the test class that tests the source method
  5) `testsToMigrate` - (optional) name of the tests that need to be migrated seperated by semicolon
 
 * After passing the inputs, run JTestMigrator (`\JTestMigrator\src\main\java\stam\testmigration\main\TestMigrator.java`)  
 * JTestMigrator produces migrated tests as output in the target application's `test` directory
 * To help with the inputs to JTestMigrator, we have included a text file `config` containing inputs that we used for each subject (`\test-migration-artifacts\Migrated-Tests\fastjson-JSON-java\method-pair-83\with-tests\config`). You can directly copy all the inputs from this file to `config.properties` file. However, you need to change the path of the source and target applications.
 
#### **How to check the results?**
 * JTestMigrator migrates a test class containing transformed tests in the `test` directory of the target library. 
 Each migrated test in the migrated test class needs to be checked manually to determine the results if any migrated test has compilation errors




