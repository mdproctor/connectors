package io.casehub.connectors.email.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class DefaultEmailInboundAccountProviderTest {

    @Test
    void blankHost_returnsEmptyList() {
        final DefaultEmailInboundAccountProvider provider =
                new DefaultEmailInboundAccountProvider("", 993, true, "user", "pass", "INBOX", 60);
        assertThat(provider.accounts()).isEmpty();
    }

    @Test
    void nullHost_returnsEmptyList() {
        final DefaultEmailInboundAccountProvider provider =
                new DefaultEmailInboundAccountProvider(null, 993, true, "user", "pass", "INBOX", 60);
        assertThat(provider.accounts()).isEmpty();
    }

    @Test
    void configuredHost_returnsSingleAccount() {
        final DefaultEmailInboundAccountProvider provider =
                new DefaultEmailInboundAccountProvider(
                        "imap.example.com", 993, true, "user@example.com",
                        "secret", "INBOX", 60);

        final List<EmailInboundAccount> accounts = provider.accounts();
        assertThat(accounts).hasSize(1);

        final EmailInboundAccount account = accounts.get(0);
        assertThat(account.id()).isEqualTo(EmailInboundConnector.ID);
        assertThat(account.host()).isEqualTo("imap.example.com");
        assertThat(account.port()).isEqualTo(993);
        assertThat(account.tls()).isTrue();
        assertThat(account.username()).isEqualTo("user@example.com");
        assertThat(account.password()).isEqualTo("secret");
        assertThat(account.folder()).isEqualTo("INBOX");
        assertThat(account.reconnectDelaySeconds()).isEqualTo(60);
    }

    @Test
    void customFolder_preservedInAccount() {
        final DefaultEmailInboundAccountProvider provider =
                new DefaultEmailInboundAccountProvider(
                        "imap.example.com", 143, false, "user", "pass", "Support", 30);
        assertThat(provider.accounts().get(0).folder()).isEqualTo("Support");
        assertThat(provider.accounts().get(0).tls()).isFalse();
        assertThat(provider.accounts().get(0).reconnectDelaySeconds()).isEqualTo(30);
    }
}
