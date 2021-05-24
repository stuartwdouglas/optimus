package io.quarkus.optimus;

import org.codehaus.plexus.component.annotations.Component;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.ArtifactDownload;
import org.eclipse.aether.spi.connector.ArtifactUpload;
import org.eclipse.aether.spi.connector.MetadataDownload;
import org.eclipse.aether.spi.connector.MetadataUpload;
import org.eclipse.aether.spi.connector.RepositoryConnector;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.checksum.ChecksumPolicyProvider;
import org.eclipse.aether.spi.connector.layout.RepositoryLayoutProvider;
import org.eclipse.aether.spi.connector.transport.TransporterProvider;
import org.eclipse.aether.spi.io.FileProcessor;
import org.eclipse.aether.transfer.NoRepositoryConnectorException;
import org.eclipse.transformer.jakarta.JakartaTransformer;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.inject.Inject;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component(role = RepositoryConnectorFactory.class, hint = "custom")
public class CustomRepositoryConnectorFactory implements RepositoryConnectorFactory {

    public static final String SUFFIX = "-$$jakarta9$$";
    BasicRepositoryConnectorFactory factory = new BasicRepositoryConnectorFactory();

    @Inject
    CustomRepositoryConnectorFactory(TransporterProvider transporterProvider, RepositoryLayoutProvider layoutProvider, ChecksumPolicyProvider checksumPolicyProvider, FileProcessor fileProcessor) {
        factory.setTransporterProvider(transporterProvider);
        factory.setRepositoryLayoutProvider(layoutProvider);
        factory.setChecksumPolicyProvider(checksumPolicyProvider);
        factory.setFileProcessor(fileProcessor);
    }

    public float getPriority() {
        return Float.MAX_VALUE;
    }

    public RepositoryConnector newInstance(RepositorySystemSession session, RemoteRepository repository) throws NoRepositoryConnectorException {
        RepositoryConnector delegate = factory.newInstance(session, repository);
        return new RepositoryConnector() {
            @Override
            public void get(Collection<? extends ArtifactDownload> artifactDownloads, Collection<? extends MetadataDownload> metadataDownloads) {
                Map<ArtifactDownload, File> jakartaArtifactDownloads = new HashMap<>();
                List<ArtifactDownload> newDownloads = new ArrayList<>();
                if (artifactDownloads != null) {
                    for (ArtifactDownload i : artifactDownloads) {
                        if (i.getArtifact().getVersion().endsWith(SUFFIX)) {
                            i.setArtifact(i.getArtifact().setVersion(i.getArtifact().getVersion().substring(0, i.getArtifact().getVersion().length() - SUFFIX.length())));
                            File old = i.getFile();
                            File newFile = new File(old.getAbsolutePath().replace(SUFFIX, "")); //blunt instrument, but it will do for now
                            i.setFile(newFile);
                            jakartaArtifactDownloads.put(i, old);
                            if (!newFile.exists()) {
                                newFile.getParentFile().mkdirs();
                                newDownloads.add(i);
                            }
                        } else {
                            newDownloads.add(i);
                        }
                    }
                }
                delegate.get(newDownloads, metadataDownloads);
                for (Map.Entry<ArtifactDownload, File> entry : jakartaArtifactDownloads.entrySet()) {
                    ArtifactDownload i = entry.getKey();
                    File toTransform = i.getFile();
                    i.setFile(entry.getValue());
                    i.setArtifact(i.getArtifact().setVersion(i.getArtifact().getVersion() + SUFFIX));
                    System.out.println(i.getFile());
                    try {
                        if (i.getArtifact().getExtension().equals("jar")) {
                            entry.getValue().getParentFile().mkdirs();
                            JakartaTransformer.main(new String[]{toTransform.getAbsolutePath(), entry.getValue().getAbsolutePath()});

                            URI uri = new URI("jar:" + entry.getValue().toURI().toASCIIString());
                            try (FileSystem zipfs = FileSystems.newFileSystem(uri, new HashMap<>())) {
                                Path e = zipfs.getPath("META-INF/quarkus-extension.properties");
                                if (Files.exists(e)) {
                                    Properties p = new Properties();
                                    try (InputStream in = Files.newInputStream(e)) {
                                        p.load(in);
                                    }
                                    Object val = p.get("deployment-artifact");
                                    if (val != null) {
                                        p.setProperty("deployment-artifact", val.toString() + SUFFIX);
                                    }
                                    try (OutputStream out = Files.newOutputStream(e)) {
                                        p.store(out, "");
                                    }
                                }
                            }
                        } else if (i.getArtifact().getExtension().equals("pom")) {
                            entry.getValue().getParentFile().mkdirs();
                            String pomFile = new String(Files.readAllBytes(toTransform.toPath()), StandardCharsets.UTF_8);
                            modifyPomFile(toTransform, entry.getValue());
                            Matcher m = Pattern.compile("<version>(.*?)</version>").matcher(pomFile);
                            StringBuffer sb = new StringBuffer();
                            StringBuffer lineBuf = new StringBuffer();
                            int indx = 0;
                            while (m.find()) {
                                m.appendReplacement(lineBuf, "<version>$1" + "-\\$\\$jakarta9\\$\\$</version>");
                                if ((indx = lineBuf.toString().lastIndexOf("<version>${project.version}-$$jakarta9$$</version>")) > 0) {
                                    sb.append(lineBuf.toString().substring(0, indx-1));
                                    sb.append("<version>${project.version}</version>");
                                } else {
                                    sb.append(lineBuf);
                                }
                                lineBuf.delete(0, lineBuf.length());
                            }
                            m.appendTail(sb);
                            Files.write(entry.getValue().toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
                            //Files.copy(toTransform.toPath(), entry.getValue().toPath());
                        } else {
                            entry.getValue().getParentFile().mkdirs();
                            Files.copy(toTransform.toPath(), entry.getValue().toPath());
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }

            private void modifyPomFile(File source, File target) {
                try {

                    DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();

                    DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();

                    Document document = documentBuilder.parse(source);

                    NodeList mainNodes = document.getFirstChild().getChildNodes();

                    for (int nc = 0; nc < mainNodes.getLength(); ++nc) {
                        Node node = mainNodes.item(nc);
                        if (node instanceof Element) {
                            Element element = (Element) node;
                            switch (node.getNodeName()) {
                                case "parent":
                                    handleParent(element);
                                    break;
                                case "dependencies":
                                    handleDependencies(element);
                                    break;
                                case "dependencyManagement":
                                    handleDependencies((Element) element.getElementsByTagName("dependencies").item(0));
                                    break;

                            }
                        }
                    }

                    // write the DOM object to the file
                    TransformerFactory transformerFactory = TransformerFactory.newInstance();

                    Transformer transformer = transformerFactory.newTransformer();
                    DOMSource domSource = new DOMSource(document);

                    StreamResult streamResult = new StreamResult(target);
                    transformer.transform(domSource, streamResult);

                } catch (Exception pce) {
                    throw new RuntimeException(pce);
                }

            }

            private void handleDependencies(Element element) {
                NodeList deps = element.getElementsByTagName("dependency");
                for (int nc = 0; nc < deps.getLength(); ++nc) {
                    Element dep = (Element) deps.item(nc);
                    Node groupId = dep.getElementsByTagName("groupId").item(0);
                    Node artifactId = dep.getElementsByTagName("artifactId").item(0);
                    NodeList versions = dep.getElementsByTagName("version");
                    Node version = versions.getLength() > 0 ? versions.item(0) : null;
                    String group = groupId.getTextContent();
                    String artifact = artifactId.getTextContent();
                    if (version != null) {
                        dep.setTextContent(dep.getTextContent() + SUFFIX);
                    }
                }
            }

            private void handleParent(Element node) {
                Node version = node.getElementsByTagName("version").item(0);
                version.setTextContent(version.getTextContent() + SUFFIX);
            }

            @Override
            public void put(Collection<? extends ArtifactUpload> artifactUploads, Collection<? extends MetadataUpload> metadataUploads) {
                delegate.put(artifactUploads, metadataUploads);
            }

            @Override
            public void close() {
                delegate.close();
            }
        };
    }


}
