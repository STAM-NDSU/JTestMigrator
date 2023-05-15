package stam.testmigration.main;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import stam.testmigration.setup.SetupTargetApp;

import java.io.File;
import java.util.ArrayList;

public class ExceptionHandler {

    SetupTargetApp setupTargetApp;
    CompilationUnit cu;
    String targetTestMethod, targetClassName;
    ExceptionHandler(CompilationUnit cu, String targetTestMethod, String targetClassName){
        this.cu = cu;
        this.targetTestMethod = targetTestMethod;
        this.targetClassName = targetClassName;
        setupTargetApp = new SetupTargetApp();
    }

    void addException(){
        NodeList<ReferenceType> targetExceptions = new NodeList<>();
        getTargetCU().accept(new VoidVisitorAdapter<Object>(){
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                if(node.getNameAsString().equals(targetTestMethod)){
                    targetExceptions.addAll(node.getThrownExceptions());
                }
            }
        }, null);

        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                ArrayList<String> annotations = new ArrayList<>();
                for(AnnotationExpr expr: node.getAnnotations()){
                    annotations.add(expr.getNameAsString());
                }
                NodeList<ReferenceType> testExceptions = new NodeList<>();
                if(node.getNameAsString().startsWith("test") || annotations.contains("Test")){
                    testExceptions.addAll(node.getThrownExceptions());
                }
                for(ReferenceType type: targetExceptions){
                    if(!testExceptions.contains(type) && !isDescendant(type, testExceptions)){
                        node.addThrownException(type);
                        addImportInTest(type);
                    }
                }
            }
        }, null);
    }

    private boolean isDescendant(ReferenceType type, NodeList<ReferenceType> testExceptions){
        for(ReferenceType exception: testExceptions){
            if(type.isDescendantOf(exception) || exception.asString().equals("Exception")){
                return true;
            }
        }
        return false;
    }

    private void addImportInTest(ReferenceType type){
        String path = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getTargetDir()), type.toString()+".java");
        if(path != null){
            String packageName = SetupTargetApp.getCompilationUnit(new File(path)).getPackageDeclaration().get().getNameAsString();
            cu.addImport(packageName+"."+type);
        }
    }

    private CompilationUnit getTargetCU(){
        String path = setupTargetApp.findFileOrDir(new File(SetupTargetApp.getTargetDir()), TestModifier.getFileNameOfInnerClass(targetClassName)+".java");
        return SetupTargetApp.getCompilationUnit(new File(path));
    }
}
