package io.casehub.connectors.email.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.store.MailFolder;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetup;
import io.casehub.connectors.InboundMessage;
import jakarta.mail.Transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class EmailInboundConnectorTest {

    // Use port 0 (OS-assigned) to avoid conflicts with GreenMailResource used by @QuarkusTest
    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(new ServerSetup[]{
            new ServerSetup(0, "localhost", ServerSetup.PROTOCOL_SMTP),
            new ServerSetup(0, "localhost", ServerSetup.PROTOCOL_IMAP)})
            .withConfiguration(GreenMailConfiguration.aConfig()
                    .withUser("inbox@example.com", "password"))
            .withPerMethodLifecycle(false);

    private List<InboundMessage> captured;
    private EmailInboundConnector connector;

    @BeforeEach
    void setUp() {
        captured = new ArrayList<>();
        connector = new EmailInboundConnector(() -> List.of(testAccount()));
    }

    @AfterEach
    void tearDown() {
        connector.stop();
    }

    private EmailInboundAccount testAccount() {
        return new EmailInboundAccount(
                "email-inbound",
                "localhost",
                GREEN_MAIL.getImap().getPort(),
                false,
                "inbox@example.com",
                "password",
                "INBOX",
                60);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void deliver(final String from, final String subject, final String body) throws Exception {
        final MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()));
        msg.setFrom(new InternetAddress(from));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress("inbox@example.com"));
        msg.setSubject(subject);
        msg.setText(body);
        msg.setSentDate(Date.from(Instant.now()));
        msg.setHeader("Message-ID", "<test-" + System.nanoTime() + "@example.com>");
        deliverViaSMTP(msg);
    }

    private void deliverViaSMTP(final MimeMessage msg) throws Exception {
        final Properties props = new Properties();
        props.put("mail.smtp.host", "localhost");
        props.put("mail.smtp.port", String.valueOf(GREEN_MAIL.getSmtp().getPort()));
        props.put("mail.smtp.auth", "true");
        final Session smtpSession = Session.getInstance(props);
        try (final Transport transport = smtpSession.getTransport("smtp")) {
            transport.connect("inbox@example.com", "password");
            // Use explicit recipient so tests for missing/unusual To: headers still work
            transport.sendMessage(msg, new jakarta.mail.Address[]{
                    new InternetAddress("inbox@example.com")});
        }
    }

    // Delivers directly to the IMAP mailbox, bypassing SMTP.
    // Use when precise header control is needed (e.g. no Message-ID, no To:).
    private void deliverDirect(final MimeMessage msg) throws Exception {
        final GreenMailUser user = GREEN_MAIL.getUserManager().getUser("inbox@example.com");
        final MailFolder inbox = GREEN_MAIL.getManagers().getImapHostManager().getInbox(user);
        inbox.appendMessage(msg, new jakarta.mail.Flags(), new Date());
    }

    // ── no-accounts ──────────────────────────────────────────────────────────

    @Test
    void noAccounts_startIsNoOp_stopIsNoOp() {
        final EmailInboundConnector empty = new EmailInboundConnector(List::of);
        empty.start(captured::add);
        empty.stop();
        assertThat(captured).isEmpty();
    }

    @Test
    void id_returnsEmailInbound() {
        assertThat(connector.id()).isEqualTo("email-inbound");
    }

    // ── delivery ─────────────────────────────────────────────────────────────

    @Test
    void noUnseenMessages_sinkNotCalled() throws Exception {
        connector.pollAccount(testAccount(), captured::add);
        assertThat(captured).isEmpty();
    }

    @Test
    void singlePlainTextMessage_deliveredWithCorrectFields() throws Exception {
        deliver("sender@example.com", "Hello subject", "Hello body");

        connector.pollAccount(testAccount(), captured::add);

        assertThat(captured).hasSize(1);
        final InboundMessage msg = captured.get(0);
        assertThat(msg.connectorId()).isEqualTo("email-inbound");
        assertThat(msg.externalSenderId()).isEqualTo("sender@example.com");
        assertThat(msg.externalChannelRef()).isEqualTo("inbox@example.com");
        assertThat(msg.content()).isEqualTo("Hello body");
        assertThat(msg.receivedAt()).isNotNull();
        assertThat(msg.metadata()).containsEntry("account-id", "email-inbound");
        assertThat(msg.metadata()).containsEntry("subject", "Hello subject");
        assertThat(msg.metadata()).containsKey("message-id");
    }

    @Test
    void multipleUnseenMessages_allDeliveredAndMarkedSeen() throws Exception {
        deliver("a@example.com", "First", "Body A");
        deliver("b@example.com", "Second", "Body B");

        connector.pollAccount(testAccount(), captured::add);

        assertThat(captured).hasSize(2);
        assertThat(captured).extracting(InboundMessage::content)
                .containsExactlyInAnyOrder("Body A", "Body B");
    }

    @Test
    void secondPoll_alreadySeenNotRedelivered() throws Exception {
        deliver("sender@example.com", "Once", "Only once");

        connector.pollAccount(testAccount(), captured::add);
        assertThat(captured).hasSize(1);

        captured.clear();
        connector.pollAccount(testAccount(), captured::add);
        assertThat(captured).isEmpty();
    }

    @Test
    void htmlOnlyMessage_rawHtmlInContent() throws Exception {
        final MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()));
        msg.setFrom(new InternetAddress("sender@example.com"));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress("inbox@example.com"));
        msg.setSubject("HTML email");
        msg.setContent("<p>Rich content</p>", "text/html; charset=UTF-8");
        msg.setSentDate(Date.from(Instant.now()));
        deliverViaSMTP(msg);

        connector.pollAccount(testAccount(), captured::add);

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).content()).isEqualTo("<p>Rich content</p>");
    }

    // ── edge cases ───────────────────────────────────────────────────────────

    @Test
    void sinkThrows_messageStillMarkedSeen_remainingDelivered() throws Exception {
        deliver("a@example.com", "First", "Body A");
        deliver("b@example.com", "Second", "Body B");

        final List<String> contents = new ArrayList<>();
        final boolean[] first = {true};
        connector.pollAccount(testAccount(), msg -> {
            contents.add(msg.content());
            if (first[0]) {
                first[0] = false;
                throw new RuntimeException("Sink error");
            }
        });

        assertThat(contents).hasSize(2);

        captured.clear();
        connector.pollAccount(testAccount(), captured::add);
        assertThat(captured).isEmpty();
    }

    @Test
    void imapConnectionFailure_loggedAndNoSinkCall() {
        final EmailInboundAccount badAccount = new EmailInboundAccount(
                "email-inbound", "localhost", 19999, false,
                "user", "pass", "INBOX", 60);

        connector.pollAccount(badAccount, captured::add);
        assertThat(captured).isEmpty();
    }

    @Test
    void missingFromHeader_senderIdIsEmptyString() throws Exception {
        final MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress("inbox@example.com"));
        msg.setSubject("No from");
        msg.setText("Body");
        msg.setSentDate(Date.from(Instant.now()));
        deliverDirect(msg); // bypass SMTP — guarantees no From: header is synthesised

        connector.pollAccount(testAccount(), captured::add);

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).externalSenderId()).isEmpty();
    }

    @Test
    void missingToHeader_channelRefFallsBackToAccountUsername() throws Exception {
        final MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()));
        msg.setFrom(new InternetAddress("sender@example.com"));
        msg.setSubject("No To");
        msg.setText("BCC body");
        msg.setSentDate(Date.from(Instant.now()));
        deliverDirect(msg); // bypass SMTP — no To: header, so can't use sendMessage

        connector.pollAccount(testAccount(), captured::add);

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).externalChannelRef()).isEqualTo("inbox@example.com");
    }

    @Test
    void messageWithoutSubject_subjectKeyAbsent() throws Exception {
        final MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()));
        msg.setFrom(new InternetAddress("sender@example.com"));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress("inbox@example.com"));
        msg.setText("Body");
        msg.setSentDate(Date.from(Instant.now()));
        deliverViaSMTP(msg);

        connector.pollAccount(testAccount(), captured::add);

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).metadata()).doesNotContainKey("subject");
    }

    // ── metadata extraction (direct — Greenmail always adds Message-ID to stored IMAP messages) ──

    @Test
    void buildMetadata_noMessageIdHeader_keyAbsent() throws Exception {
        final MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()));
        msg.setFrom(new InternetAddress("sender@example.com"));
        msg.setSubject("Has subject, no message-id");
        msg.setText("Body");
        // No Message-ID header set — test buildMetadata() directly since IMAP servers always add one

        final Map<String, String> metadata = EmailInboundConnector.buildMetadata(testAccount(), msg);

        assertThat(metadata).containsKey("account-id");
        assertThat(metadata).doesNotContainKey("message-id");
        assertThat(metadata).containsEntry("subject", "Has subject, no message-id");
    }

    @Test
    void buildMetadata_noSubject_keyAbsent() throws Exception {
        final MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()));
        msg.setFrom(new InternetAddress("sender@example.com"));
        msg.setHeader("Message-ID", "<test@example.com>");
        msg.setText("Body");
        // No Subject header set

        final Map<String, String> metadata = EmailInboundConnector.buildMetadata(testAccount(), msg);

        assertThat(metadata).containsEntry("account-id", EmailInboundConnector.ID);
        assertThat(metadata).containsEntry("message-id", "<test@example.com>");
        assertThat(metadata).doesNotContainKey("subject");
    }
}
