package io.casehub.connectors;

/**
 * SPI for <em>pull-based</em> inbound message transports (e.g. IMAP polling).
 *
 * <p>Implementations are CDI {@code @ApplicationScoped} beans discovered at startup.
 * {@link InboundConnectorService} calls {@link #start(InboundMessageSink)} at startup
 * and {@link #stop()} at shutdown.
 *
 * <p>Webhook-based transports do <strong>not</strong> implement this interface;
 * they extend {@link WebhookInboundConnector} instead, which has no lifecycle methods.
 *
 * <h2>ID contract</h2>
 * {@code id()} must be lowercase, URL-safe, no slashes or spaces (pattern:
 * {@code [a-z0-9][a-z0-9\-]*}). Validated at registration — a violation causes
 * startup failure.
 */
public interface InboundConnector {

    /**
     * Unique identifier for this connector type.
     * Examples: {@code "email-inbound"}, {@code "jira-inbound"}.
     *
     * @return lowercase, URL-safe id; must not be null or blank
     */
    String id();

    /**
     * Start receiving messages. Called once at Quarkus startup.
     *
     * @param sink the callback to invoke when a message arrives
     */
    void start(InboundMessageSink sink);

    /** Stop receiving messages. Called at Quarkus shutdown. */
    void stop();
}
