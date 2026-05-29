package io.casehub.connectors;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.quarkus.arc.All;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

/**
 * CDI service that manages pull-based {@link InboundConnector} lifecycle and acts
 * as the single CDI event bus for all inbound messages.
 *
 * <p>At startup, calls {@link InboundConnector#start(InboundMessageSink)} on every
 * registered pull connector, passing {@code this::receive} as the sink. At shutdown,
 * calls {@link InboundConnector#stop()} on all.
 *
 * <p>{@code WebhookRouter} also calls {@link #receive(InboundMessage)} directly for
 * messages delivered via HTTP webhook, ensuring a single CDI event bus regardless of
 * transport.
 *
 * <h2>CDI event is synchronous</h2>
 * {@code receive()} fires a synchronous {@code Event<InboundMessage>}. Observers must
 * not perform blocking I/O inline — Slack's retry deadline is 3 seconds. The Qhorus
 * bridge (connectors#6) must dispatch asynchronously before writing to the database.
 *
 * <h2>ID validation</h2>
 * Connector ids must be lowercase, URL-safe, no slashes or spaces
 * (pattern: {@code [a-z0-9][a-z0-9\-]*}). Violated ids cause startup failure.
 */
@ApplicationScoped
public class InboundConnectorService {

    private static final Logger LOG = Logger.getLogger(InboundConnectorService.class.getName());

    private final Map<String, InboundConnector> pullRegistry;
    private final Consumer<InboundMessage> eventBus;

    /** CDI constructor — Quarkus injects all InboundConnector beans and the CDI Event. */
    @Inject
    InboundConnectorService(@All final List<InboundConnector> pullConnectors,
                            final Event<InboundMessage> messageEvent) {
        this(pullConnectors, messageEvent::fire);
    }

    /** Package-private constructor for unit tests — accepts a recording consumer. */
    InboundConnectorService(final List<InboundConnector> pullConnectors,
                            final Consumer<InboundMessage> eventBus) {
        this.eventBus = eventBus;
        pullConnectors.forEach(c -> validateId(c.id()));
        this.pullRegistry = pullConnectors.stream().collect(Collectors.toMap(
                InboundConnector::id,
                c -> c,
                (a, b) -> {
                    throw new IllegalStateException(
                            "Duplicate inbound connector id: '" + a.id() + "'");
                }));
    }

    void onStart(@Observes final StartupEvent ignored) {
        pullRegistry.values().forEach(c -> {
            LOG.info("Starting pull connector: " + c.id());
            c.start(this::receive);
        });
    }

    void onStop(@Observes final ShutdownEvent ignored) {
        pullRegistry.values().forEach(c -> {
            LOG.info("Stopping pull connector: " + c.id());
            c.stop();
        });
    }

    /**
     * Fire a CDI {@code Event<InboundMessage>} for the received message.
     *
     * <p>Called by pull connectors via the sink, and directly by
     * {@code WebhookRouter} for webhook-based connectors.
     *
     * @param message the received message; must not be null
     */
    public void receive(final InboundMessage message) {
        eventBus.accept(message);
    }

    /** Returns the ids of all registered pull connectors. */
    public Set<String> pullIds() {
        return Set.copyOf(pullRegistry.keySet());
    }

    private static void validateId(final String id) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9\\-]*")) {
            throw new IllegalStateException(
                    "InboundConnector id '" + id
                    + "' is invalid — must be lowercase, URL-safe, no slashes or spaces"
                    + " (pattern: [a-z0-9][a-z0-9-]*)");
        }
    }
}
