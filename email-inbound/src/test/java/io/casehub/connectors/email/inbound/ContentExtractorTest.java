package io.casehub.connectors.email.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import org.junit.jupiter.api.Test;

// Note: newly-constructed MimeMessage objects default Content-Type to "text/plain" until
// saveChanges() is called. Messages fetched from IMAP already have correct headers — so
// the implementation is correct. Tests call saveChanges() to simulate that committed state.

class ContentExtractorTest {

    private static Session emptySession() {
        return Session.getInstance(new Properties());
    }

    @Test
    void plainText_returnedDirectly() throws Exception {
        final MimeMessage msg = new MimeMessage(emptySession());
        msg.setText("Hello world", "UTF-8");
        assertThat(ContentExtractor.extractContent(msg)).isEqualTo("Hello world");
    }

    @Test
    void htmlOnly_returnedAsRawHtml() throws Exception {
        final MimeMessage msg = new MimeMessage(emptySession());
        msg.setContent("<p>Hello</p>", "text/html; charset=UTF-8");
        assertThat(ContentExtractor.extractContent(msg)).isEqualTo("<p>Hello</p>");
    }

    @Test
    void multipartAlternative_preferPlainText() throws Exception {
        final MimeMessage msg = new MimeMessage(emptySession());
        final MimeMultipart mp = new MimeMultipart("alternative");

        final MimeBodyPart plain = new MimeBodyPart();
        plain.setText("Plain text body", "UTF-8");

        final MimeBodyPart html = new MimeBodyPart();
        html.setContent("<p>HTML body</p>", "text/html; charset=UTF-8");

        mp.addBodyPart(plain);
        mp.addBodyPart(html);
        msg.setContent(mp);
        msg.saveChanges(); // commits Content-Type header

        assertThat(ContentExtractor.extractContent(msg)).isEqualTo("Plain text body");
    }

    @Test
    void multipartMixedWithNestedAlternative_extractsPlainText() throws Exception {
        final MimeMessage msg = new MimeMessage(emptySession());
        final MimeMultipart mixed = new MimeMultipart("mixed");

        final MimeMultipart alternative = new MimeMultipart("alternative");
        final MimeBodyPart plain = new MimeBodyPart();
        plain.setText("Plain body", "UTF-8");
        final MimeBodyPart html = new MimeBodyPart();
        html.setContent("<p>HTML body</p>", "text/html; charset=UTF-8");
        alternative.addBodyPart(plain);
        alternative.addBodyPart(html);

        final MimeBodyPart alternativeWrapper = new MimeBodyPart();
        alternativeWrapper.setContent(alternative);
        mixed.addBodyPart(alternativeWrapper);

        final MimeBodyPart attachment = new MimeBodyPart();
        attachment.setContent("pdf bytes", "application/pdf");
        attachment.setDisposition(Part.ATTACHMENT);
        attachment.setFileName("report.pdf");
        mixed.addBodyPart(attachment);

        msg.setContent(mixed);
        msg.saveChanges(); // commits Content-Type header

        assertThat(ContentExtractor.extractContent(msg)).isEqualTo("Plain body");
    }

    @Test
    void multipartWithOnlyAttachment_returnsEmptyString() throws Exception {
        // multipart/mixed with only a binary part — no text body
        final MimeMessage msg = new MimeMessage(emptySession());
        final MimeMultipart mixed = new MimeMultipart("mixed");

        final MimeBodyPart attachment = new MimeBodyPart();
        attachment.setContent(new byte[]{1, 2, 3}, "application/pdf");
        attachment.setDisposition(Part.ATTACHMENT);
        attachment.setFileName("file.pdf");
        mixed.addBodyPart(attachment);

        msg.setContent(mixed);
        msg.saveChanges(); // commits Content-Type header

        assertThat(ContentExtractor.extractContent(msg)).isEmpty();
    }
}
