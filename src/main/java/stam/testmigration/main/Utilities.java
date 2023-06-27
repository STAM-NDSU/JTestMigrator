package stam.testmigration.main;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

public class Utilities {

    public static NodeList<ClassOrInterfaceType> getExtendedTypes(CompilationUnit cu){
        NodeList<ClassOrInterfaceType> extendedTypes = new NodeList<>();
        if(cu.getTypes().isNonEmpty() && cu.getType(0).isClassOrInterfaceDeclaration()){
            extendedTypes.addAll(cu.getType(0).asClassOrInterfaceDeclaration().getExtendedTypes());
        }
        return extendedTypes;
    }
}
