package io.quarkus.optimus;

import org.codehaus.plexus.component.annotations.Component;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.transport.GetTask;
import org.eclipse.aether.spi.connector.transport.PeekTask;
import org.eclipse.aether.spi.connector.transport.PutTask;
import org.eclipse.aether.spi.connector.transport.Transporter;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transfer.NoTransporterException;
import org.eclipse.aether.transport.wagon.WagonConfigurator;
import org.eclipse.aether.transport.wagon.WagonProvider;
import org.eclipse.aether.transport.wagon.WagonTransporterFactory;

import javax.inject.Inject;
import java.net.URI;

//@Component(role = TransporterFactory.class, hint = "custom")
public class TransformingTransport implements TransporterFactory {

    WagonTransporterFactory delegate;

    @Inject
    public TransformingTransport(WagonProvider wagonProvider, WagonConfigurator wagonConfigurator) {
        delegate = new WagonTransporterFactory();
        delegate.setWagonConfigurator(wagonConfigurator);
        delegate.setWagonProvider(wagonProvider);
    }

    @Override
    public Transporter newInstance(RepositorySystemSession session, RemoteRepository repository) throws NoTransporterException {
        Transporter wagon = delegate.newInstance(session, repository);
        return new Transporter() {
            @Override
            public int classify(Throwable error) {
                return wagon.classify(error);
            }

            @Override
            public void peek(PeekTask task) throws Exception {
                if (task.getLocation().toString().contains("-$$jakarta9$$")) {
                    PeekTask newTast = new PeekTask(new URI(task.getLocation().toASCIIString().replace("-$$jakarta9$$","")));
                    System.out.println("Peek " + newTast.getLocation());
                    wagon.peek(newTast);
                } else {
                    wagon.peek(task);
                }
            }

            @Override
            public void get(GetTask task) throws Exception {
                if (task.getLocation().toString().contains("-$$jakarta9$$")) {
                    GetTask newTast = new GetTask(new URI(task.getLocation().toASCIIString().replace("-$$jakarta9$$","")));
                    newTast.setListener(task.getListener());
                    System.out.println("get " + task.getDataString());
                    wagon.get(newTast);
                    task.newOutputStream().write(newTast.getDataBytes());
                } else {
                    wagon.get(task);
                }

            }

            @Override
            public void put(PutTask task) throws Exception {
                wagon.put(task);
            }

            @Override
            public void close() {
                wagon.close();
            }
        };
    }

    @Override
    public float getPriority() {
        return Float.MAX_VALUE;
    }
}
