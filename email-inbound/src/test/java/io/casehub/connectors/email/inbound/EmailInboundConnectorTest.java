package io.casehub.connectors.email.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import io.casehub.connectors.Attachment;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.store.MailFolder;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetup;
import io.casehub.connectors.InboundMessage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

class EmailInboundConnectorTest {

    // Port 0 = OS-assigned, avoids conflict with GreenMailResource (fixed ports for @QuarkusTest)
    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(new ServerSetup[]{
            new ServerSetup(0, "localhost", ServerSetup.PROTOCOL_SMTP),
            new ServerSetup(0, "localhost", ServerSetup.PROTOCOL_IMAP)})
            .withConfiguration(GreenMailConfiguration.aConfig()
                    .withUser("inbox@example.com", "password"))
            .withPerMethodLifecycle(false);

    private LinkedBlockingQueue<InboundMessage> captured;
    private EmailInboundConnector connector;

    @BeforeEach
    void setUp() {
        captured = new LinkedBlockingQueue<>();
        connector = new EmailInboundConnector(() -> List.of(testAccount()));
    }

    @AfterEach
    void tearDown() {
        connector.stop();
    }

    private EmailInboundAccount testAccount() {
        return new EmailInboundAccount(
                "email-inbound", "localhost", GREEN_MAIL.getImap().getPort(),
                false, "inbox@example.com", "password", "INBOX", 60);
    }

    /** Blocks until the IDLE loop delivers a message, fails after 3 s. */
    private InboundMessage receive() throws InterruptedException {
        final InboundMessage msg = captured.poll(3, TimeUnit.SECONDS);
        assertThat(msg).as("message not delivered within 3s — IDLE did not fire").isNotNull();
        return msg;
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
        try (final Transport transport = Session.getInstance(props).getTransport("smtp")) {
            transport.connect("inbox@example.com", "password");
            transport.sendMessage(msg, new jakarta.mail.Address[]{
                    new InternetAddress("inbox@example.com")});
        }
    }

    // Appends directly to IMAP mailbox — use for header-edge-case tests.
    // Pre-deliver BEFORE start() so processUnseen() on first connect catches it.
    private void deliverDirect(final MimeMessage msg) throws Exception {
        final GreenMailUser user = GREEN_MAIL.getUserManager().getUser("inbox@example.com");
        final MailFolder inbox = GREEN_MAIL.getManagers().getImapHostManager().getInbox(user);
        inbox.appendMessage(msg, new jakarta.mail.Flags(), new Date());
    }

    // ── identity and guard ────────────────────────────────────────────────────

    @Test
    void id_returnsEmailInbound() {
        assertThat(connector.id()).isEqualTo("email-inbound");
    }

    @Test
    @Timeout(5)
    void noAccounts_startIsNoOp_stopIsNoOp() {
        final EmailInboundConnector empty = new EmailInboundConnector(List::of);
        empty.start(captured::add);
        empty.stop();
        assertThat(captured).isEmpty();
    }

    @Test
    @Timeout(5)
    void doubleStart_isNoOp() throws Exception {
        connector.start(captured::add);
        connector.start(captured::add); // second call must not subscribe a second IDLE loop

        deliver("sender@example.com", "Subject", "Body");

        receive(); // exactly one delivery
        assertThat(captured.poll(500, TimeUnit.MILLISECONDS))
                .as("second delivery arrived — double-start guard failed")
                .isNull();
    }

    // ── delivery ─────────────────────────────────────────────────────────────

    @Test
    @Timeout(5)
    void singlePlainTextMessage_deliveredWithCorrectFields() throws Exception {
        connector.start(captured::add);
        deliver("sender@example.com", "Hello subject", "Hello body");

        final InboundMessage msg = receive();
        assertThat(msg.connectorId()).isEqualTo("email-inbound");
        assertThat(msg.externalSenderId()).isEqualTo("sender@example.com");
        assertThat(msg.externalChannelRef()).isEqualTo("inbox@example.com");
        assertThat(msg.content()).isEqualTo("Hello body");
        assertThat(msg.receivedAt()).isNotNull();
        assertThat(msg.metadata()).containsEntry("account-id", "email-inbound");
        assertThat(msg.metadata()).containsEntry("subject", "Hello subject");
        assertThat(msg.metadata()).containsKey("message-id");
        assertThat(msg.metadata()).containsEntry("attachment-count", "0");
    }

    @Test
    @Timeout(5)
    void multipleUnseenMessages_allDelivered() throws Exception {
        connector.start(captured::add);
        deliver("a@example.com", "First", "Body A");
        deliver("b@example.com", "Second", "Body B");

        final InboundMessage m1 = receive();
        final InboundMessage m2 = receive();
        assertThat(List.of(m1.content(), m2.content()))
                .containsExactlyInAnyOrder("Body A", "Body B");
    }

    @Test
    @Timeout(10)
    void messageMarkedSeen_notRedeliveredAfterRestart() throws Exception {
        connector.start(captured::add);
        deliver("sender@example.com", "Once", "Only once");
        receive();
        connector.stop();

        final LinkedBlockingQueue<InboundMessage> second = new LinkedBlockingQueue<>();
        final EmailInboundConnector connector2 =
                new EmailInboundConnector(() -> List.of(testAccount()));
        try {
            connector2.start(second::add);
            assertThat(second.poll(1, TimeUnit.SECONDS)).as("message redelivered").isNull();
        } finally {
            connector2.stop();
        }
    }

    @Test
    @Timeout(5)
    void sinkThrows_messageStillMarkedSeen_remainingDelivered() throws Exception {
        deliver("a@example.com", "First", "Body A");
        deliver("b@example.com", "Second", "Body B");

        final LinkedBlockingQueue<String> contents = new LinkedBlockingQueue<>();
        final boolean[] first = {true};
        connector.start(msg -> {
            contents.add(msg.content());
            if (first[0]) { first[0] = false; throw new RuntimeException("Sink error"); }
        });

        assertThat(contents.poll(3, TimeUnit.SECONDS)).as("first message").isNotNull();
        assertThat(contents.poll(3, TimeUnit.SECONDS)).as("second message").isNotNull();

        // Neither redelivered after restart
        connector.stop();
        final LinkedBlockingQueue<InboundMessage> after = new LinkedBlockingQueue<>();
        final EmailInboundConnector connector2 =
                new EmailInboundConnector(() -> List.of(testAccount()));
        try {
            connector2.start(after::add);
            assertThat(after.poll(1, TimeUnit.SECONDS)).as("redelivery after sink-threw").isNull();
        } finally {
            connector2.stop();
        }
    }

    @Test
    @Timeout(5)
    void htmlOnlyMessage_rawHtmlInContent() throws Exception {
        connector.start(captured::add);
        final MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()));
        msg.setFrom(new InternetAddress("sender@example.com"));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress("inbox@example.com"));
        msg.setSubject("HTML email");
        msg.setContent("<p>Rich content</p>", "text/html; charset=UTF-8");
        msg.setSentDate(Date.from(Instant.now()));
        deliverViaSMTP(msg);

        assertThat(receive().content()).isEqualTo("<p>Rich content</p>");
    }

    @Test
    @Timeout(5)
    void imapConnectionFailure_loggedAndNoSinkCall() throws Exception {
        final LinkedBlockingQueue<InboundMessage> q = new LinkedBlockingQueue<>();
        final EmailInboundConnector bad = new EmailInboundConnector(() -> List.of(
                new EmailInboundAccount("bad", "localhost", 19999, false,
                        "u", "p", "INBOX", 1)));
        bad.start(q::add);
        assertThat(q.poll(500, TimeUnit.MILLISECONDS)).isNull();
        bad.stop();
    }

    // ── edge cases (pre-deliver before start so processUnseen() on connect handles them) ──

    @Test
    @Timeout(5)
    void missingFromHeader_senderIdIsEmptyString() throws Exception {
        final MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress("inbox@example.com"));
        msg.setSubject("No from");
        msg.setText("Body");
        msg.setSentDate(Date.from(Instant.now()));
        deliverDirect(msg); // pre-deliver before start()

        connector.start(captured::add);
        assertThat(receive().externalSenderId()).isEmpty();
    }

    @Test
    @Timeout(5)
    void missingToHeader_channelRefFallsBackToAccountUsername() throws Exception {
        final MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()));
        msg.setFrom(new InternetAddress("sender@example.com"));
        msg.setSubject("No To");
        msg.setText("BCC body");
        msg.setSentDate(Date.from(Instant.now()));
        deliverDirect(msg); // pre-deliver before start()

        connector.start(captured::add);
        assertThat(receive().externalChannelRef()).isEqualTo("inbox@example.com");
    }

    @Test
    @Timeout(5)
    void messageWithoutSubject_subjectKeyAbsent() throws Exception {
        connector.start(captured::add);
        final MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()));
        msg.setFrom(new InternetAddress("sender@example.com"));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress("inbox@example.com"));
        msg.setText("Body");
        msg.setSentDate(Date.from(Instant.now()));
        deliverViaSMTP(msg);

        assertThat(receive().metadata()).doesNotContainKey("subject");
    }

    // ── buildMetadata (direct call — no IMAP needed) ──────────────────────────

    @Test
    void buildMetadata_noMessageIdHeader_keyAbsent() throws Exception {
        final MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()));
        msg.setFrom(new InternetAddress("sender@example.com"));
        msg.setSubject("Has subject, no message-id");
        msg.setText("Body");

        final Map<String, String> metadata = EmailInboundConnector.buildMetadata(testAccount(), msg, 0);

        assertThat(metadata).containsKey("account-id");
        assertThat(metadata).doesNotContainKey("message-id");
        assertThat(metadata).containsEntry("subject", "Has subject, no message-id");
        assertThat(metadata).containsEntry("attachment-count", "0");
    }

    @Test
    void buildMetadata_noSubject_keyAbsent() throws Exception {
        final MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()));
        msg.setFrom(new InternetAddress("sender@example.com"));
        msg.setHeader("Message-ID", "<test@example.com>");
        msg.setText("Body");

        final Map<String, String> metadata = EmailInboundConnector.buildMetadata(testAccount(), msg, 3);

        assertThat(metadata).containsEntry("account-id", EmailInboundConnector.ID);
        assertThat(metadata).containsEntry("message-id", "<test@example.com>");
        assertThat(metadata).doesNotContainKey("subject");
        assertThat(metadata).containsEntry("attachment-count", "3");
    }

    // ── attachment delivery (Phase 2) ─────────────────────────────────────────

    @Test
    @Timeout(5)
    void messageWithPdfAttachment_attachmentDelivered() throws Exception {
        connector.start(captured::add);

        final MimeMessage raw = new MimeMessage(Session.getInstance(new Properties()));
        raw.setFrom(new InternetAddress("sender@example.com"));
        raw.setRecipient(Message.RecipientType.TO, new InternetAddress("inbox@example.com"));
        raw.setSubject("Invoice");
        raw.setSentDate(Date.from(Instant.now()));

        final MimeMultipart multipart = new MimeMultipart();
        final MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText("See attached");
        multipart.addBodyPart(textPart);
        final MimeBodyPart attPart = new MimeBodyPart();
        attPart.setContent(new byte[]{1, 2, 3}, "application/pdf");
        attPart.setDisposition(jakarta.mail.Part.ATTACHMENT);
        attPart.setFileName("invoice.pdf");
        multipart.addBodyPart(attPart);
        raw.setContent(multipart);

        deliverViaSMTP(raw);

        final InboundMessage msg = receive();
        assertThat(msg.content()).isEqualTo("See attached");
        assertThat(msg.attachments()).hasSize(1);
        assertThat(msg.attachments().get(0).filename()).isEqualTo("invoice.pdf");
        assertThat(msg.attachments().get(0).contentType()).isEqualTo("application/pdf");
        assertThat(msg.attachments().get(0).content()).isEqualTo(new byte[]{1, 2, 3});
        assertThat(msg.metadata()).containsEntry("attachment-count", "1");
    }

    @Test
    @Timeout(5)
    void messageWithNoAttachments_attachmentsEmptyAndCountIsZero() throws Exception {
        connector.start(captured::add);
        deliver("sender@example.com", "Plain", "Body");

        final InboundMessage msg = receive();
        assertThat(msg.attachments()).isEmpty();
        assertThat(msg.metadata()).containsEntry("attachment-count", "0");
    }

    @Test
    @Timeout(5)
    void messageWithMultipleAttachments_allCollected() throws Exception {
        connector.start(captured::add);

        final MimeMessage raw = new MimeMessage(Session.getInstance(new Properties()));
        raw.setFrom(new InternetAddress("sender@example.com"));
        raw.setRecipient(Message.RecipientType.TO, new InternetAddress("inbox@example.com"));
        raw.setSubject("Files");
        raw.setSentDate(Date.from(Instant.now()));

        final MimeMultipart multipart = new MimeMultipart();
        final MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText("Two files");
        multipart.addBodyPart(textPart);

        final MimeBodyPart pdf = new MimeBodyPart();
        pdf.setContent(new byte[]{1}, "application/pdf");
        pdf.setDisposition(jakarta.mail.Part.ATTACHMENT);
        pdf.setFileName("a.pdf");
        multipart.addBodyPart(pdf);

        final MimeBodyPart img = new MimeBodyPart();
        img.setContent(new byte[]{2, 3}, "image/png");
        img.setDisposition(jakarta.mail.Part.ATTACHMENT);
        img.setFileName("b.png");
        multipart.addBodyPart(img);

        raw.setContent(multipart);
        deliverViaSMTP(raw);

        final InboundMessage msg = receive();
        assertThat(msg.attachments()).hasSize(2);
        assertThat(msg.attachments()).extracting(Attachment::filename)
                .containsExactlyInAnyOrder("a.pdf", "b.png");
        assertThat(msg.metadata()).containsEntry("attachment-count", "2");
    }
}
