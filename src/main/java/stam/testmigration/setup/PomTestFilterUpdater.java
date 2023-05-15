package stam.testmigration.setup;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import stam.testmigration.search.CodeSearchResults;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.util.ArrayList;
import java.util.Objects;

public class PomTestFilterUpdater {

    static Document originalDocument;

    public void addTestFilter(){
        File targetPomFile = getPomFile(SetupTargetApp.getTargetDir());
        Document document = getPomDoc(targetPomFile);
        originalDocument = getPomDoc(targetPomFile);

        String packageName = new SetupTargetApp().getPackageName(new CodeSearchResults().getTargetFileName(), SetupTargetApp.getTargetDir());
        addFilter(document, packageName);

        writeXML(document, targetPomFile);
    }

    private void addFilter(Document document, String packageName){

        ArrayList<Node> plugin = new ArrayList<>();
        NodeList nodeList = document.getElementsByTagName("artifactId");
        for(int i=0; i<nodeList.getLength(); i++){
            String value = nodeList.item(i).getTextContent();
            if(value.equals("maven-surefire-plugin")){
                plugin.add(nodeList.item(i).getParentNode());
            }
        }
        if(plugin.isEmpty()){
            addPulginNode(document, packageName+"."+ SetupTargetApp.getTestFileNameInTarget());
        }else{
            addFilterNode(plugin.get(0), document, packageName+"."+SetupTargetApp.getTestFileNameInTarget());
        }
    }

    private void addPulginNode(Document document, String fullTestFileName){
        //Node pluginNode = createPluginNode(document, fullTestFileName);
        NodeList nodeList = document.getElementsByTagName("build");
        if(nodeList.getLength() != 0){
            Node pluginsNode = getChildNode(nodeList.item(0), "plugins");
            if(pluginsNode != null){
                addPluginNode(pluginsNode, document, fullTestFileName);
            }else{
                Element plugins = document.createElement("plugins");
                addPluginNode(plugins, document, fullTestFileName);
                nodeList.item(0).appendChild(plugins);
            }
        }else{
            Node parent = document.getElementsByTagName("parent").item(0);
            Element build = document.createElement("build");
            parent.appendChild(build);
            Element plugins = document.createElement("plugins");
            build.appendChild(plugins);
            addPluginNode(plugins, document, fullTestFileName);
        }
    }

    private void addPluginNode(Node node, Document document, String fullTestFileName){
        Element plugin = document.createElement("plugin");
        node.appendChild(plugin);

        Element groupId = document.createElement("groupId");
        groupId.setTextContent("org.apache.maven.plugins");
        plugin.appendChild(groupId);

        Element artifactId = document.createElement("artifactId");
        artifactId.setTextContent("maven-surefire-plugin");
        plugin.appendChild(artifactId);

        Element version = document.createElement("version");
        version.setTextContent("3.0.0-M5");
        plugin.appendChild(version);

        Element configuration = document.createElement("configuration");
        plugin.appendChild(configuration);
        addChildNode(configuration, document, fullTestFileName);
    }


    private void addFilterNode(Node pluginNode, Document document, String fullTestFileName){

        Node version = getChildNode(pluginNode, "version");
        if(version != null){
            version.setTextContent("3.0.0-M5");
        }else if(pluginNode.getNodeType() == Node.ELEMENT_NODE){
            Element pluginElement = (Element) pluginNode;
            Element versionElement = document.createElement("version");
            versionElement.setTextContent("3.0.0-M5");
            pluginElement.appendChild(versionElement);
        }

        Node configuration = getChildNode(pluginNode, "configuration");
        if(configuration != null){
            removeChild(configuration);
            addChildNode(configuration, document, fullTestFileName);
        }else{
            Element configurationNode = document.createElement("configuration");
            addChildNode(configurationNode, document, fullTestFileName);
            pluginNode.appendChild(configurationNode);
        }
    }

    private void addChildNode(Node node, Document document, String testFileName) {
        Element includes = document.createElement("includes");
        node.appendChild(includes);
        Element include = document.createElement("include");
        include.setTextContent(testFileName);
        includes.appendChild(include);
    }

    private void removeChild(Node node){
        while (node.hasChildNodes()){
            node.removeChild(node.getFirstChild());
        }
    }

    private Node getChildNode(Node node, String childName){
        NodeList childNodes = node.getChildNodes();
        for(int i=0; i<childNodes.getLength(); i++){
            if(childNodes.item(i).getNodeName().equals(childName)){
                return childNodes.item(i);
            }
        }
        return null;
    }

    File getPomFile(String projectRootDir){
        String appDir = new SetupTargetApp().findFileOrDir(new File(projectRootDir), "src");
        appDir = appDir.substring(0, appDir.lastIndexOf("src"));
        Objects.requireNonNull(appDir, "Could not find src directory in the project "+projectRootDir+".");

        File pomFile = new File(appDir+"pom.xml");
        if(!pomFile.exists()){
            System.out.println("pom.xml file does not exist in "+appDir);
            System.exit(0);
        }
        return pomFile;
    }

    private Document getPomDoc(File pomFile){
        Document document = null;
        if(pomFile != null){
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder;
            try {
                dBuilder = dbFactory.newDocumentBuilder();
                document = dBuilder.parse(pomFile);
            } catch (Exception e) {
                e.printStackTrace();
            }
            document.getDocumentElement().normalize();
        }
        return document;
    }

    private void writeXML(Document document, File pomFile){
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.transform(new DOMSource(document), new StreamResult(pomFile));
        } catch (TransformerException e) {
            e.printStackTrace();
        }
    }

    public void removeTestFilter(){
        writeXML(originalDocument, getPomFile(SetupTargetApp.getTargetDir()));
    }
}
