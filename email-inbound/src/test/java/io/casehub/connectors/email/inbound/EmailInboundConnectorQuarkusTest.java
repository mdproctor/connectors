package io.casehub.connectors.email.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import io.casehub.connectors.InboundMessage;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Full CDI integration test: verifies that EmailInboundConnector is discovered by
 * InboundConnectorService, polls the Greenmail IMAP inbox, and fires a CDI Event<InboundMessage>.
 */
@QuarkusTest
@QuarkusTestResource(GreenMailResource.class)
class EmailInboundConnectorQuarkusTest {

    @Inject
    InboundMessageCapture capture;

    @BeforeEach
    void clearCapture() {
        capture.clear();
    }

    @Test
    void happyPath_emailDelivered_cdiFired() throws Exception {
        // Send a plain-text email via Greenmail's SMTP server
        final Properties props = new Properties();
        props.put("mail.smtp.host", "localhost");
        props.put("mail.smtp.port", String.valueOf(GreenMailResource.INSTANCE.getSmtp().getPort()));
        props.put("mail.smtp.auth", "true");

        final MimeMessage msg = new MimeMessage(Session.getInstance(props));
        msg.setFrom(new InternetAddress("sender@example.com"));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress("inbox@example.com"));
        msg.setSubject("Integration test");
        msg.setText("Integration body");
        msg.setSentDate(Date.from(Instant.now()));

        try (final Transport transport = Session.getInstance(props).getTransport("smtp")) {
            transport.connect("inbox@example.com", "password");
            transport.sendMessage(msg, msg.getAllRecipients());
        }

        // poll-interval-seconds=1 — wait up to 2s for async CDI event delivery
        final InboundMessage delivered = capture.poll(2, TimeUnit.SECONDS);
        assertThat(delivered).isNotNull();
        assertThat(delivered.connectorId()).isEqualTo("email-inbound");
        assertThat(delivered.externalSenderId()).isEqualTo("sender@example.com");
        assertThat(delivered.externalChannelRef()).isEqualTo("inbox@example.com");
        assertThat(delivered.content()).isEqualTo("Integration body");
        assertThat(delivered.metadata()).containsEntry("account-id", "email-inbound");
        assertThat(delivered.metadata()).containsEntry("subject", "Integration test");
    }
}
