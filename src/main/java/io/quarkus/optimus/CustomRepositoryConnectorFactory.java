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

import javax.inject.Inject;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                            ReverseJakartaTransformer.main(new String[]{toTransform.getAbsolutePath(), entry.getValue().getAbsolutePath()});
                        } else if (i.getArtifact().getExtension().equals("pom")) {
                            entry.getValue().getParentFile().mkdirs();
                            Files.copy(toTransform.toPath(), entry.getValue().toPath());
                        } else {
                            entry.getValue().getParentFile().mkdirs();
                            Files.copy(toTransform.toPath(), entry.getValue().toPath());
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
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
