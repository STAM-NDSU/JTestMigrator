package stam.testmigration.main;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.google.common.collect.Iterables;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.text.similarity.LevenshteinDistance;
import stam.testmigration.setup.ConfigurationRetriever;
import stam.testmigration.setup.SetupTargetApp;

import java.io.File;
import java.util.*;

public class TypeReplacer {

    void replaceSimilarTypes(CompilationUnit cu){
        Map<String, String> classPairs = new HashMap<>();
        for(String sourceType : getSourceTypes(cu)){
            if(!isHelper(sourceType)){
                String targetType = findSimilarTargetType(sourceType);
                if(targetType != null) classPairs.put(sourceType, targetType);
            }
        }
        replaceTypes(classPairs, cu);
        //TODO: replace constructor arguments
    }

    private boolean isHelper(String className){
        String filePath = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getSourceDir()), className+".java");
        return (filePath != null && filePath.contains(File.separator+"test"+File.separator));
    }

    private void replaceTypes(Map<String, String> classPairs, CompilationUnit cu){
        Set<String> types = new HashSet<>();
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(ObjectCreationExpr expr, Object arg){
                super.visit(expr, arg);
                String type = expr.getTypeAsString();
                if(classPairs.containsKey(type)){
                    String targetType = classPairs.get(type);
                    new ConstructorMapper().findTargetConstructor(expr, targetType);
                    expr.setType(targetType);
                    types.add(targetType);
                }else if(MethodMatcher.sourceTargetClass.containsKey(type)){
                    new ConstructorMapper().findTargetConstructor(expr, Iterables.getOnlyElement(MethodMatcher.sourceTargetClass.get(type)));
                    String targetType = Iterables.getOnlyElement(MethodMatcher.sourceTargetClass.get(type));
                    expr.setType(targetType);
                    types.add(targetType);
                }
            }

            @Override
            public void visit(VariableDeclarator expr, Object arg){
                super.visit(expr, arg);
                String type = expr.getTypeAsString();
                if(classPairs.containsKey(type)){
                    String targetType = classPairs.get(type);
                    expr.setType(targetType);
                    types.add(targetType);
                }else if(MethodMatcher.sourceTargetClass.containsKey(type)){
                    String targetType = Iterables.getOnlyElement(MethodMatcher.sourceTargetClass.get(type));
                    expr.setType(targetType);
                    types.add(targetType);
                }
            }

            @Override
            public void visit(CastExpr expr, Object arg){
                super.visit(expr, arg);
                String type = expr.getTypeAsString();
                if(classPairs.containsKey(type)){
                    String targetType = classPairs.get(type);
                    expr.setType(targetType);
                    types.add(targetType);
                }else if(MethodMatcher.sourceTargetClass.containsKey(type)){
                    String targetType = Iterables.getOnlyElement(MethodMatcher.sourceTargetClass.get(type));
                    expr.setType(targetType);
                    types.add(targetType);
                }
            }

            @Override
            public void visit(FieldDeclaration declaration, Object arg){
                super.visit(declaration, arg);
                String type = declaration.getElementType().asString();
                if(classPairs.containsKey(type)){
                    declaration.getVariables().forEach(var -> {
                        String targetType = classPairs.get(type);
                        var.setType(targetType);
                        types.add(targetType);
                    });
                }else if(MethodMatcher.sourceTargetClass.containsKey(type)){
                    declaration.getVariables().forEach(var -> {
                        String targetType = Iterables.getOnlyElement(MethodMatcher.sourceTargetClass.get(type));
                        var.setType(targetType);
                        types.add(targetType);
                    });
                }
            }

            @Override
            public void visit(CatchClause catchClause, Object arg){
                super.visit(catchClause, arg);
                String type = catchClause.getParameter().getTypeAsString();
                if(classPairs.containsKey(type)){
                    String targetType = classPairs.get(type);
                    catchClause.getParameter().setType(targetType);
                    types.add(targetType);
                }else if(MethodMatcher.sourceTargetClass.containsKey(type)){
                    String targetType = Iterables.getOnlyElement(MethodMatcher.sourceTargetClass.get(type));
                    catchClause.getParameter().setType(targetType);
                    types.add(targetType);
                }
            }
            @Override
            public void visit(Parameter parameter, Object arg){
                super.visit(parameter, arg);
                String type = parameter.getTypeAsString();
                if(classPairs.containsKey(type)){
                    String targetType = classPairs.get(type);
                    parameter.setType(targetType);
                    types.add(targetType);
                }else if(MethodMatcher.sourceTargetClass.containsKey(type)){
                    String targetType = Iterables.getOnlyElement(MethodMatcher.sourceTargetClass.get(type));
                    parameter.setType(targetType);
                    types.add(targetType);
                }
            }
            @Override
            public void visit(ClassExpr expr, Object arg){
                super.visit(expr, arg);
                String type = expr.getTypeAsString();
                if(classPairs.containsKey(type)){
                    String targetType = classPairs.get(type);
                    expr.setType(targetType);
                    types.add(targetType);
                }else if(MethodMatcher.sourceTargetClass.containsKey(type)){
                    String targetType = Iterables.getOnlyElement(MethodMatcher.sourceTargetClass.get(type));
                    expr.setType(targetType);
                    types.add(targetType);
                }
            }
        }, null);

        types.forEach(type -> addImport(type, cu));
    }

    private void addImport(String type, CompilationUnit cu){
        String targetPath = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getTargetDir()), type+".java");
        if(targetPath != null){
            cu.addImport(SetupTargetApp.getCompilationUnit(new File(targetPath)).getType(0).getFullyQualifiedName().get());
        }
    }

    private ArrayList<String> getSourceTypes(CompilationUnit cu){
        ArrayList<String> sourceTypes = new ArrayList<>();
        cu.accept(new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(ObjectCreationExpr expr, Object arg){
                super.visit(expr, arg);
                String type = validateType(expr.getTypeAsString());
                if(type != null && !sourceTypes.contains(type)){
                    sourceTypes.add(type);
                }
            }
            @Override
            public void visit(VariableDeclarator declarator, Object arg){
                super.visit(declarator, arg);
                String type = validateType(declarator.getTypeAsString());
                if(type != null && !sourceTypes.contains(type)){
                    sourceTypes.add(type);
                }
            }
            @Override
            public void visit(FieldDeclaration declaration, Object arg){
                super.visit(declaration, arg);
                String type = validateType(declaration.getElementType().asString());
                if(type != null && !sourceTypes.contains(type)){
                    sourceTypes.add(type);
                }
            }
            @Override
            public void visit(CatchClause catchClause, Object arg){
                super.visit(catchClause, arg);
                String type = validateType(catchClause.getParameter().getTypeAsString());
                if(type != null && !sourceTypes.contains(type)){
                    sourceTypes.add(type);
                }
            }
            @Override
            public void visit(Parameter parameter, Object arg){
                super.visit(parameter, arg);
                String type = validateType(parameter.getTypeAsString());
                if(type != null && !sourceTypes.contains(type)){
                    sourceTypes.add(type);
                }
            }
            @Override
            public void visit(ClassExpr expr, Object arg){
                super.visit(expr, arg);
                String type = validateType(expr.getTypeAsString());
                if(type != null && !sourceTypes.contains(type)){
                    sourceTypes.add(type);
                }
            }
            @Override
            public void visit(CastExpr expr, Object arg){
                super.visit(expr, arg);
                String type = validateType(expr.getTypeAsString());
                if(type != null && !sourceTypes.contains(type)){
                    sourceTypes.add(type);
                }
            }
        }, null);
        return sourceTypes;
    }

    private String validateType(String type){
        String path = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getSourceDir()), type+".java");
        String targetPath = new SetupTargetApp().findFileOrDir(new File(SetupTargetApp.getTargetDir()), type+".java");
        if(path != null && targetPath == null){
            return type;
        }
        return null;
    }

    private String findSimilarTargetType(String sourceType){
        MethodMatcher methodMatcher = new MethodMatcher();
        LevenshteinDistance levenshteinDistance = new LevenshteinDistance();
        String targetType = null;
        double simScore = 0;
        double levSimScore = 0;

        Stack<File> stack = methodMatcher.getTargetFilesStack(sourceType);
        while(!stack.isEmpty()){
            File child = stack.pop();
            if (child.isDirectory()) {
                for(File f : Objects.requireNonNull(child.listFiles())) stack.push(f);
            } else if (methodMatcher.isJavaFile(child) && isUsableFile(child) && !child.getAbsolutePath().contains("src"+File.separator+"test")){
                String targetClassName = FilenameUtils.removeExtension(child.getName());
                double vecScore = methodMatcher.calculateVecSimilarity(sourceType, targetClassName);
                double levScore = levenshteinDistance.apply(sourceType.toLowerCase(), targetClassName.toLowerCase());
                if(vecScore > ConfigurationRetriever.thresholdValue && vecScore > simScore){
                    simScore = vecScore;
                    levSimScore = levScore;
                    targetType = targetClassName;
                }else if(vecScore > ConfigurationRetriever.thresholdValue && vecScore == simScore && levScore < levSimScore){
                    levSimScore = levScore;
                    targetType = targetClassName;
                }
                //TODO: use method names similarity also to check class similarity
            }
        }
        return targetType;
    }

    private boolean isUsableFile(File targetFile){
        CompilationUnit targetCU = SetupTargetApp.getCompilationUnit(targetFile);
        return targetCU.getTypes().isNonEmpty() && targetCU.getType(0).isClassOrInterfaceDeclaration()
                && !targetCU.getType(0).asClassOrInterfaceDeclaration().isInterface()
                && !targetCU.getType(0).asClassOrInterfaceDeclaration().isAbstract();
    }
}
