package stam.testmigration.main;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import stam.testmigration.setup.SetupTargetApp;

import java.io.File;
import java.util.ArrayList;
import java.util.Objects;

public class ReturnTypeAdjuster {

    void adjustReturnType(CompilationUnit cu, File testFile, String targetMethod){
        Objects.requireNonNull(SetupTargetApp.getTestCompilationFromTargetApp(testFile)).accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(VariableDeclarationExpr expr, Object arg){
                super.visit(expr, arg);
                ArrayList<MethodCallExpr> callExprs = getReplacedMethod(expr, targetMethod);
                if(!callExprs.isEmpty()){
                    try {
                        //get return tyoe of the last method invoked
                        String returnType = callExprs.get(callExprs.size()-1).resolve().getReturnType().describe();
                        if(returnType.contains(".") && returnType.contains("<")){
                            returnType = returnType.substring(0, returnType.indexOf("<"));
                            returnType = returnType.substring(returnType.lastIndexOf(".")+1);
                        }else if(returnType.contains(".")){
                            returnType = returnType.substring(returnType.lastIndexOf(".")+1);
                        }
                        returnType = sanitizeType(returnType);
                        String exprType = sanitizeType(expr.getElementType().asString());

                        //if the return type is not the same as the variable declaration type,
                        // convert the return type or replace the declaration type
                        if(isConvertibleType(returnType, exprType)){
                            convertType(cu, returnType, expr);
                        }else{
                            replaceVariableDeclarationType(cu, returnType, exprType, expr);
                        }

                    }catch(UnsolvedSymbolException ignored){}
                }
            }
        }, null);
    }

    private void convertType(CompilationUnit cu, String returnType, VariableDeclarationExpr expr){
        cu.accept(new VoidVisitorAdapter<Object>() {
            Statement originalStmt, replacementStmt;
            @Override
            public void visit(VariableDeclarationExpr variableDeclarationExpr, Object arg){
                super.visit(variableDeclarationExpr, arg);
                if(expr.toString().equals(variableDeclarationExpr.toString())){
                    originalStmt = StaticJavaParser.parseStatement(variableDeclarationExpr.toString() + ";");
                    replacementStmt = getConvertedTypeStmt(variableDeclarationExpr, returnType);
                }
            }
            @Override
            public void visit(MethodDeclaration node, Object arg){
                super.visit(node, arg);
                if(node.getBody().isPresent() && node.getBody().get().getStatements().contains(originalStmt)){
                    node.getBody().get().getStatements().replace(originalStmt, replacementStmt);
                }
            }
        }, null);
    }

    private Statement getConvertedTypeStmt(VariableDeclarationExpr variableDeclarationExpr, String returnType){
        if(returnType.equals("Iterable")){
            return StaticJavaParser.parseStatement(variableDeclarationExpr.toString()+".iterator();");
        }
        return StaticJavaParser.parseStatement(variableDeclarationExpr.toString()+";");
    }

    private boolean isConvertibleType(String returnType, String exprType){
        //add convertible types
        if(exprType.equals("Iterator") && returnType.equals("Iterable")){
            return true;
        }
        return false;
    }

    private String sanitizeType(String typeString){
        if(typeString.contains("<")){
            return typeString.substring(0, typeString.indexOf("<"));
        }
        return typeString;
    }

    private void replaceVariableDeclarationType(CompilationUnit cu, String returnType, String exprType, VariableDeclarationExpr expr){
        if(!exprType.equals(returnType)){
            cu.accept(new VoidVisitorAdapter<Object>() {
                Statement originalStmt, replacementStmt;
                @Override
                public void visit(VariableDeclarationExpr variableDeclarationExpr, Object arg){
                    super.visit(variableDeclarationExpr, arg);
                    if(expr.toString().equals(variableDeclarationExpr.toString())){
                        originalStmt = StaticJavaParser.parseStatement(variableDeclarationExpr.toString() + ";");
                        replacementStmt = getReplacedTypeStmt(variableDeclarationExpr, returnType);
                    }
                }
                @Override
                public void visit(MethodDeclaration node, Object arg){
                    super.visit(node, arg);
                    if(node.getBody().isPresent() && node.getBody().get().getStatements().contains(originalStmt)){
                        node.getBody().get().getStatements().replace(originalStmt, replacementStmt);
                    }
                }
            }, null);
        }
    }

    private Statement getReplacedTypeStmt(VariableDeclarationExpr variableDeclarationExpr, String returnType){
        VariableDeclarationExpr clonedExpr = variableDeclarationExpr.clone();
        NodeList<Modifier> modifiers = clonedExpr.getModifiers();
        for(Modifier modifier: modifiers){
            clonedExpr.removeModifier(modifier.getKeyword());
        }

        String nodeString = clonedExpr.toString();
        if(!returnType.equals("void")){
            nodeString = clonedExpr.toString().replaceFirst(clonedExpr.getElementType().asString(), returnType);
        }
        StringBuilder builder = new StringBuilder();
        for(Modifier modifier: modifiers){
            builder.append(modifier.getKeyword().asString()).append(" ");
        }
        return StaticJavaParser.parseStatement(builder.toString()+nodeString + ";");
    }

    private ArrayList<MethodCallExpr> getReplacedMethod(VariableDeclarationExpr expr, String targetMethod){
        ArrayList<MethodCallExpr> callExprs = new ArrayList<>();
        final boolean[] containsReplacedMethod = {false};
        expr.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(MethodCallExpr callExpr, Object arg){
                super.visit(callExpr, arg);
                callExprs.add(callExpr);
                if(TestModifier.replacedMethods.containsKey(callExpr.getNameAsString()) || targetMethod.equals(callExpr.getNameAsString())){
                    containsReplacedMethod[0] = true;
                }
            }
        }, null);
        if(containsReplacedMethod[0]){
            return callExprs;
        }
        return new ArrayList<MethodCallExpr>();
    }
}
