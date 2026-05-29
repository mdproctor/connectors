package io.casehub.connectors.email.inbound;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.arc.DefaultBean;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Default {@link EmailInboundAccountProvider} — reads a single IMAP account from
 * MP Config. Returns an empty list when {@code host} is blank (connector is inactive).
 *
 * <p>Override by providing an {@code @ApplicationScoped} bean without {@code @DefaultBean}.
 */
@DefaultBean
@ApplicationScoped
public class DefaultEmailInboundAccountProvider implements EmailInboundAccountProvider {

    @ConfigProperty(name = "casehub.connectors.email-inbound.host", defaultValue = "")
    String host;

    @ConfigProperty(name = "casehub.connectors.email-inbound.port", defaultValue = "993")
    int port;

    @ConfigProperty(name = "casehub.connectors.email-inbound.tls", defaultValue = "true")
    boolean tls;

    @ConfigProperty(name = "casehub.connectors.email-inbound.username", defaultValue = "")
    String username;

    @ConfigProperty(name = "casehub.connectors.email-inbound.password", defaultValue = "")
    String password;

    @ConfigProperty(name = "casehub.connectors.email-inbound.folder", defaultValue = "INBOX")
    String folder;

    @ConfigProperty(name = "casehub.connectors.email-inbound.poll-interval-seconds", defaultValue = "60")
    int pollIntervalSeconds;

    DefaultEmailInboundAccountProvider() {}

    DefaultEmailInboundAccountProvider(final String host, final int port, final boolean tls,
                                       final String username, final String password,
                                       final String folder, final int pollIntervalSeconds) {
        this.host = host;
        this.port = port;
        this.tls = tls;
        this.username = username;
        this.password = password;
        this.folder = folder;
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    @Override
    public List<EmailInboundAccount> accounts() {
        if (host == null || host.isBlank()) {
            return List.of();
        }
        return List.of(new EmailInboundAccount(
                EmailInboundConnector.ID, host, port, tls, username, password, folder, pollIntervalSeconds));
    }
}
