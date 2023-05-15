package stam.testmigration.main;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.apache.commons.io.FileUtils;
import stam.testmigration.search.CodeSearchResults;
import stam.testmigration.setup.SetupTargetApp;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class TestHelper {
    SetupTargetApp setupTargetApp = new SetupTargetApp();
    CodeSearchResults searchResults = new CodeSearchResults();
    TestModifier testModifier = new TestModifier();

    void checkHelper(CompilationUnit cu, String sourceClassName) {

        String testFile = SetupTargetApp.getTestFileNameInTarget();
        String path = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getTargetDir()), testFile);
        File testDir = new File(path).getParentFile();

        Map<String, String> unresolvedSymb = checkUnresolvedFieldOrMethod(cu, sourceClassName);
        if(!unresolvedSymb.isEmpty()) {
            for(String sym: unresolvedSymb.keySet()) {
                //move helper class to target app test dir
                List<String> pathString = Arrays.asList(unresolvedSymb.get(sym).split("\\\\"));
                if(pathString.contains("test") && !MethodMatcher.processedHelperClasses.contains(sym)){
                    try {
                        FileUtils.copyFileToDirectory(new File(unresolvedSymb.get(sym)), testDir, false);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    //modify helper class
                    File helperFile = new File(testDir.getAbsoluteFile()+File.separator+sym+".java");
                    CompilationUnit cuHelper = SetupTargetApp.getCompilationUnit(helperFile);
                    testModifier.replacePackage(cuHelper, testFile);
                    testModifier.removeSourceImports(cuHelper, sym+".java");
                    addStaticFields(cu, cuHelper);
                    testModifier.commitChanges(cuHelper, helperFile);
                }
            }
        }

    }

    //add static fields in helper class
    private void addStaticFields(CompilationUnit cu, CompilationUnit cuHelper) {

        String sourceFileName = searchResults.getSourceFileName();
        String sourceClassName = sourceFileName.substring(0, sourceFileName.indexOf("."));

        NodeList<FieldDeclaration> staticSourceFD = testModifier.getStaticFields(sourceClassName);
        List<FieldDeclaration> fdNodesTest = cu.getType(0).getFields();

        for(FieldDeclaration fd : fdNodesTest) {
            if(fd.isStatic() && staticSourceFD.contains(fd))
                cuHelper.getType(0).getMembers().add(0, fd);
        }
    }

    //Find unresolved symbols (field decl or method call) in test file
    private Map<String, String> checkUnresolvedFieldOrMethod(CompilationUnit cu, String sourceClassName) {
        List<String> potentialUnresolvedSymb = new ArrayList<>();
        Map<String, String> unresolvedSymb = new HashMap<>();
        Map<String, String> nameTypePair = new HashMap<>();
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(FieldDeclaration node, Object arg) {
                super.visit(node, arg);
                if(node.getElementType().isClassOrInterfaceType()) {
                    String type = node.getElementType().toString();
                    potentialUnresolvedSymb.add(type);
                    for(VariableDeclarator vd : node.getVariables()){
                        String name = vd.getNameAsString();
                        if(!nameTypePair.containsKey(name))
                            nameTypePair.put(name, type);
                    }
                }
            }
            @Override
            public void visit(VariableDeclarator node, Object arg){
                super.visit(node, arg);
                if(node.getType().isClassOrInterfaceType()) {
                    potentialUnresolvedSymb.add(node.getTypeAsString());
                    if(!nameTypePair.containsKey(node.getNameAsString()))
                        nameTypePair.put(node.getNameAsString(), node.getTypeAsString());
                }
            }
            @Override
            public void visit(MethodCallExpr node, Object arg) {
                super.visit(node, arg);
                if(node.getScope().isPresent() && !node.getScope().get().toString().equals(sourceClassName)){
                    String var = node.getScope().get().toString();
                    if(nameTypePair.containsKey(var)){
                        potentialUnresolvedSymb.add(nameTypePair.get(var));
                    }else{
                        potentialUnresolvedSymb.add(var);
                    }
                }
            }

        }, null);

        potentialUnresolvedSymb.remove("File");
        //if the symbol is located in source app, it is an unresolved symbol
        for(String sym : potentialUnresolvedSymb) {
            String path = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getSourceDir()), sym+".java");
            if(path != null)
                unresolvedSymb.put(sym, path);
        }
        return unresolvedSymb;
    }

    //move resources, such as files, used in the source app to target app dir
    void moveResources(CompilationUnit cu){
        List<String> fileNames = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<>() {
            @Override
            public void visit(MethodCallExpr node, Object arg){
                super.visit(node, arg);
                Optional<Node> parent = node.getParentNode();
                if(node.getNameAsString().equals("getResourceAsStream") && parent.isPresent()){
                    if(parent.get().toString().startsWith("IOUtils")){
                        if(node.getArgument(0).isStringLiteralExpr())
                            fileNames.add(node.getArgument(0).toStringLiteralExpr().get().asString());
                    }
                }
            }
        }, null);

        List<File> files = new ArrayList<>();
        fileNames.forEach(name -> {
            String path = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getSourceDir()), name);
            if(path != null)
                files.add(new File(path));
        });

        if(!files.isEmpty()){
            String path = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getTargetDir()), "src");
            if(path != null){
                File dir = new File(path+File.separator+"test"+File.separator+"resources");
                dir.mkdir();
                files.forEach(file -> {
                    try {
                        FileUtils.copyFileToDirectory(file, dir);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            }
        }
    }

}
