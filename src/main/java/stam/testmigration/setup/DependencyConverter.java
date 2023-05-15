package stam.testmigration.setup;

import org.apache.maven.model.Model;
import org.jdom2.Element;
import org.jdom2.Namespace;

import java.util.ArrayList;

public class DependencyConverter {
    private String groupId, artifactId, version, scope, classifier;
    private boolean management;

    public DependencyConverter(String groupId, String artifactId, String version,
                               String scope, Boolean management, String classifier) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.scope = scope;
        this.management = management;
        this.classifier = classifier;
    }

    public Element toJDomElement(Namespace namespace){
        Element dependency = new Element("dependency", namespace);
        if(groupId != null){
            dependency.addContent(new Element("groupId", namespace).setText(groupId));
        }
        if(artifactId != null){
            dependency.addContent(new Element("artifactId", namespace).setText(artifactId));
        }
        if(version != null){
            dependency.addContent(new Element("version", namespace).setText(version));
        }else {
            dependency.addContent(new Element("version", namespace).setText("LATEST"));
        }
        if(classifier != null){
            dependency.addContent(new Element("classifier", namespace).setText(classifier));
        }
        if(scope != null && scope.equals("test")){
            dependency.addContent(new Element("scope", namespace).setText("test"));
        }
        return dependency;
    }

    public String toGradleFormat(String scope){
        return scope+" '"+groupId+":"+artifactId+":"+version+"'";
    }

    public String toPureGradleFormat(){
        if(groupId != null && artifactId != null && version != null){
            return groupId+":"+artifactId+":"+version;
        }
        return null;
    }

    public boolean isSameDependency(String gradleDependency) {
        String ignoreVersionOther = gradleDependency.substring(0, gradleDependency.lastIndexOf(":"));
        String ignoreVersionThis = groupId+":"+artifactId;
        return ignoreVersionOther.equals(ignoreVersionThis);
    }

    public boolean isInTargetConverters(ArrayList<DependencyConverter> converters){
        for(DependencyConverter converter: converters){
            if(converter.getArtifactId().equals(this.artifactId)){
                return true;
            }
        }
        return false;
    }

    public void resolvePomVersion(Model model){
        if(this.version != null && this.version.startsWith("$")){
            String versionVar = this.version.substring(2, this.version.length()-1);
            this.version = model.getProperties().getProperty(versionVar);
        }
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }

    public String getScope() {
        return scope;
    }

    public Boolean getManagement() {
        return management;
    }

    @Override
    public String toString() {
        return "DependencyConverter{" +
                "groupId='" + groupId + '\'' +
                ", artifactId='" + artifactId + '\'' +
                ", version='" + version + '\'' +
                ", scope='" + scope + '\'' +
                ", management=" + management +
                '}';
    }
}
