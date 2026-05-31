package io.casehub.connectors.email.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import io.casehub.connectors.Attachment;

import org.junit.jupiter.api.Test;

// Note: newly-constructed MimeMessage objects default Content-Type to "text/plain" until
// saveChanges() is called. Messages fetched from IMAP already have correct headers — so
// the implementation is correct. Tests call saveChanges() to simulate that committed state.

class ContentExtractorTest {

    private static Session emptySession() {
        return Session.getInstance(new Properties());
    }

    // ── text content (existing cases updated to ExtractionResult API) ──────────

    @Test
    void plainText_returnedInContent_noAttachments() throws Exception {
        final MimeMessage msg = new MimeMessage(emptySession());
        msg.setText("Hello world", "UTF-8");

        final ExtractionResult result = ContentExtractor.extract(msg);
        assertThat(result.content()).isEqualTo("Hello world");
        assertThat(result.attachments()).isEmpty();
    }

    @Test
    void htmlOnly_returnedAsRawHtml_noAttachments() throws Exception {
        final MimeMessage msg = new MimeMessage(emptySession());
        msg.setContent("<p>Hello</p>", "text/html; charset=UTF-8");

        final ExtractionResult result = ContentExtractor.extract(msg);
        assertThat(result.content()).isEqualTo("<p>Hello</p>");
        assertThat(result.attachments()).isEmpty();
    }

    @Test
    void multipartAlternative_prefersPlainText() throws Exception {
        final MimeMessage msg = new MimeMessage(emptySession());
        final MimeMultipart mp = new MimeMultipart("alternative");

        final MimeBodyPart plain = new MimeBodyPart();
        plain.setText("Plain text body", "UTF-8");

        final MimeBodyPart html = new MimeBodyPart();
        html.setContent("<p>HTML body</p>", "text/html; charset=UTF-8");

        mp.addBodyPart(plain);
        mp.addBodyPart(html);
        msg.setContent(mp);
        msg.saveChanges();

        final ExtractionResult result = ContentExtractor.extract(msg);
        assertThat(result.content()).isEqualTo("Plain text body");
        assertThat(result.attachments()).isEmpty();
    }

    @Test
    void multipartMixedWithNestedAlternative_extractsPlainTextAndPdf() throws Exception {
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

        // Binary attachment alongside text body
        final MimeBodyPart attachment = new MimeBodyPart();
        attachment.setContent("pdf bytes".getBytes(), "application/pdf");
        attachment.setDisposition(Part.ATTACHMENT);
        attachment.setFileName("report.pdf");
        mixed.addBodyPart(attachment);

        msg.setContent(mixed);
        msg.saveChanges();

        final ExtractionResult result = ContentExtractor.extract(msg);
        assertThat(result.content()).isEqualTo("Plain body");
        assertThat(result.attachments()).hasSize(1);
        assertThat(result.attachments().get(0).filename()).isEqualTo("report.pdf");
        assertThat(result.attachments().get(0).contentType()).isEqualTo("application/pdf");
    }

    // ── attachment extraction ──────────────────────────────────────────────────

    @Test
    void multipartWithOnlyBinaryAttachment_emptyContent_attachmentPresent() throws Exception {
        final MimeMessage msg = new MimeMessage(emptySession());
        final MimeMultipart mixed = new MimeMultipart("mixed");

        final MimeBodyPart att = new MimeBodyPart();
        att.setContent(new byte[]{1, 2, 3}, "application/pdf");
        att.setDisposition(Part.ATTACHMENT);
        att.setFileName("file.pdf");
        mixed.addBodyPart(att);

        msg.setContent(mixed);
        msg.saveChanges();

        final ExtractionResult result = ContentExtractor.extract(msg);
        assertThat(result.content()).isEmpty();
        assertThat(result.attachments()).hasSize(1);
        assertThat(result.attachments().get(0).filename()).isEqualTo("file.pdf");
        assertThat(result.attachments().get(0).contentType()).isEqualTo("application/pdf");
        assertThat(result.attachments().get(0).content()).isEqualTo(new byte[]{1, 2, 3});
    }

    @Test
    void multipleAttachments_allCollected() throws Exception {
        final MimeMessage msg = new MimeMessage(emptySession());
        final MimeMultipart mixed = new MimeMultipart("mixed");

        final MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText("Body", "UTF-8");
        mixed.addBodyPart(textPart);

        final MimeBodyPart pdf = new MimeBodyPart();
        pdf.setContent(new byte[]{1}, "application/pdf");
        pdf.setDisposition(Part.ATTACHMENT);
        pdf.setFileName("a.pdf");
        mixed.addBodyPart(pdf);

        final MimeBodyPart img = new MimeBodyPart();
        img.setContent(new byte[]{2, 3}, "image/png");
        img.setDisposition(Part.ATTACHMENT);
        img.setFileName("b.png");
        mixed.addBodyPart(img);

        msg.setContent(mixed);
        msg.saveChanges();

        final ExtractionResult result = ContentExtractor.extract(msg);
        assertThat(result.content()).isEqualTo("Body");
        assertThat(result.attachments()).hasSize(2);
        assertThat(result.attachments()).extracting(Attachment::filename)
                .containsExactlyInAnyOrder("a.pdf", "b.png");
    }

    @Test
    void attachmentWithNoFilename_filenameIsNull() throws Exception {
        final MimeMessage msg = new MimeMessage(emptySession());
        final MimeMultipart mixed = new MimeMultipart("mixed");

        final MimeBodyPart att = new MimeBodyPart();
        att.setContent(new byte[]{9}, "application/octet-stream");
        att.setDisposition(Part.ATTACHMENT);
        // no setFileName()
        mixed.addBodyPart(att);

        msg.setContent(mixed);
        msg.saveChanges();

        final ExtractionResult result = ContentExtractor.extract(msg);
        assertThat(result.attachments()).hasSize(1);
        assertThat(result.attachments().get(0).filename()).isNull();
    }

    @Test
    void contentTypeParametersStripped_lowercased() throws Exception {
        final MimeMessage msg = new MimeMessage(emptySession());
        final MimeMultipart mixed = new MimeMultipart("mixed");

        final MimeBodyPart att = new MimeBodyPart();
        // Content-Type with parameters and mixed case
        att.setContent(new byte[]{1}, "Application/PDF; name=\"invoice.pdf\"");
        att.setDisposition(Part.ATTACHMENT);
        att.setFileName("invoice.pdf");
        mixed.addBodyPart(att);

        msg.setContent(mixed);
        msg.saveChanges();

        final ExtractionResult result = ContentExtractor.extract(msg);
        assertThat(result.attachments().get(0).contentType()).isEqualTo("application/pdf");
    }

    @Test
    void textCalendar_collectedAsAttachment_notContent() throws Exception {
        // text/calendar (iCal) is a text/* subtype but not text/plain or text/html —
        // it goes to attachments so observers can process it as structured data
        final MimeMessage msg = new MimeMessage(emptySession());
        final MimeMultipart mixed = new MimeMultipart("mixed");

        final MimeBodyPart body = new MimeBodyPart();
        body.setText("See invite", "UTF-8");
        mixed.addBodyPart(body);

        final MimeBodyPart cal = new MimeBodyPart();
        cal.setContent("BEGIN:VCALENDAR\nEND:VCALENDAR", "text/calendar; method=REQUEST");
        cal.setDisposition(Part.ATTACHMENT);
        cal.setFileName("invite.ics");
        mixed.addBodyPart(cal);

        msg.setContent(mixed);
        msg.saveChanges();

        final ExtractionResult result = ContentExtractor.extract(msg);
        assertThat(result.content()).isEqualTo("See invite");
        assertThat(result.attachments()).hasSize(1);
        assertThat(result.attachments().get(0).contentType()).isEqualTo("text/calendar");
    }
}
