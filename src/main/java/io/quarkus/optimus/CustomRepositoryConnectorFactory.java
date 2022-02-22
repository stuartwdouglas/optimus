package io.quarkus.optimus;

import org.codehaus.plexus.component.annotations.Component;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.DependencyVisitor;
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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component(role = RepositoryConnectorFactory.class, hint = "custom")
public class CustomRepositoryConnectorFactory implements RepositoryConnectorFactory {

    public static final String SUFFIX = "-$$jakarta9$$";
    final BasicRepositoryConnectorFactory factory = new BasicRepositoryConnectorFactory();

    final RepositorySystem repositorySystem;

    final OldDependencyManager oldDependencyManager = OldDependencyManager.createDefault();

    final Set<String> noTransformNeeded = Collections.newSetFromMap(new ConcurrentHashMap<>());


    @Inject
    CustomRepositoryConnectorFactory(TransporterProvider transporterProvider, RepositoryLayoutProvider layoutProvider, ChecksumPolicyProvider checksumPolicyProvider, FileProcessor fileProcessor, RepositorySystem repositorySystem) {
        factory.setTransporterProvider(transporterProvider);
        factory.setRepositoryLayoutProvider(layoutProvider);
        factory.setChecksumPolicyProvider(checksumPolicyProvider);
        factory.setFileProcessor(fileProcessor);
        this.repositorySystem = repositorySystem;
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
                    String originalVersion = i.getArtifact().getVersion();
                    i.setArtifact(i.getArtifact().setVersion(originalVersion + SUFFIX));
                    System.out.println(i.getFile());
                    try {
                        if (i.getArtifact().getExtension().equals("jar")) {
                            entry.getValue().getParentFile().mkdirs();

                            org.eclipse.transformer.Transformer jTrans = new org.eclipse.transformer.Transformer(new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
                            jTrans.setOptionDefaults(JakartaTransformer.class, JakartaTransformer.getOptionDefaults());
                            jTrans.setArgs(new String[]{toTransform.getAbsolutePath(), entry.getValue().getAbsolutePath()});
                            jTrans.run();

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
                            Artifact artifact = new DefaultArtifact(i.getArtifact().getGroupId(), i.getArtifact().getArtifactId(), i.getArtifact().getExtension(), originalVersion);
                            List<Artifact> deps = resolveDependencies(artifact, repository, session);
                            modifyPomFile(toTransform, entry.getValue(), deps, originalVersion);
                        } else {
                            entry.getValue().getParentFile().mkdirs();
                            Files.copy(toTransform.toPath(), entry.getValue().toPath());
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }

            private void modifyPomFile(File source, File target, List<Artifact> deps, String currentVersion) {
                try {
                    Map<String, String> actualVersionMap = new HashMap<>();
                    for (Artifact i : deps) {
                        actualVersionMap.put(i.getGroupId() + ":" + i.getArtifactId(), i.getVersion());
                    }

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
                                    handleDependencies(element, actualVersionMap, currentVersion);
                                    break;
                                case "dependencyManagement":
                                    handleDependencies((Element) element.getElementsByTagName("dependencies").item(0), actualVersionMap, currentVersion);
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

            private void handleDependencies(Element element, Map<String, String> actualVersionMap, String currentVersion) {
                NodeList deps = element.getElementsByTagName("dependency");
                for (int nc = 0; nc < deps.getLength(); ++nc) {
                    Element dep = (Element) deps.item(nc);
                    Node groupId = dep.getElementsByTagName("groupId").item(0);
                    Node artifactId = dep.getElementsByTagName("artifactId").item(0);
                    NodeList versions = dep.getElementsByTagName("version");
                    Node version = versions.getLength() > 0 ? versions.item(0) : null;
                    String group = groupId.getTextContent();
                    String artifact = artifactId.getTextContent();
                    String resolvedVersion = actualVersionMap.get(group + ":" + artifact);
                    String versionText = version == null ? null : version.getTextContent();
                    if (resolvedVersion != null && version != null) {
                        versionText = resolvedVersion;
                    }
                    if ("${project.version}".equals(versionText)) {
                        versionText = currentVersion;
                    }

                    String id = group + ":"+ artifact +":" + versionText;
                    if (noTransformNeeded.contains(id)) {
                        continue;
                    }
                    if (oldDependencyManager.isOldApi(group, artifact, versionText)) {
                        Artifact replacement = oldDependencyManager.getReplacement(group, artifact);
                        groupId.setTextContent(replacement.getGroupId());
                        artifactId.setTextContent(replacement.getArtifactId());
                        if (version != null) {
                            version.setTextContent(replacement.getVersion());
                        }
                    } else if (version != null) {
                        if (version.getTextContent().endsWith(SUFFIX)) {
                            continue;
                        }
                        Artifact af = new DefaultArtifact(group, artifact, "jar", versionText);
                        List<Artifact> resolveDependencies = resolveDependencies(af, repository, session);
                        {
                            for (Artifact a : resolveDependencies) {
                                if (oldDependencyManager.isOldApi(a.getGroupId(), a.getArtifactId(), a.getVersion())) {
                                    version.setTextContent(versionText + SUFFIX);
                                    break;
                                }
                            }
                        }
                        noTransformNeeded.add(id);
                    } else {
                        noTransformNeeded.add(id);
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

    private List<Artifact> resolveDependencies(org.eclipse.aether.artifact.Artifact i, RemoteRepository repository, RepositorySystemSession session) {
        Dependency dep = new Dependency(i, "compile");
        CollectRequest request = new CollectRequest(dep, Collections.singletonList(repository));

        List<Artifact> ret = new ArrayList<>();
        try {
            CollectResult result = repositorySystem.collectDependencies(session, request);
            result.getRoot().accept(new DependencyVisitor() {
                @Override
                public boolean visitEnter(DependencyNode node) {
                    ret.add(node.getArtifact());
                    return true;
                }

                @Override
                public boolean visitLeave(DependencyNode node) {
                    return true;
                }
            });
        } catch (DependencyCollectionException e) {
            throw new RuntimeException(e);
        }
        return ret;
    }


}
