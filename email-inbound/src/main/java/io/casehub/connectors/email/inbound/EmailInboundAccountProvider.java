package io.casehub.connectors.email.inbound;

import java.util.List;

/**
 * SPI — returns the IMAP accounts to poll.
 *
 * <p>Override {@link DefaultEmailInboundAccountProvider} by providing an
 * {@code @ApplicationScoped} bean without {@code @DefaultBean}. The default
 * implementation reads a single account from MP Config.
 */
@FunctionalInterface
public interface EmailInboundAccountProvider {
    List<EmailInboundAccount> accounts();
}
