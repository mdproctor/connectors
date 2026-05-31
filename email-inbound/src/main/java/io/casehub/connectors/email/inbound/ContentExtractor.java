package io.casehub.connectors.email.inbound;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;

import io.casehub.connectors.Attachment;

/**
 * Single-pass recursive MIME content extractor.
 *
 * <p>Traverses the MIME tree once, collecting:
 * <ul>
 *   <li>{@code text/plain} → body content (preferred)</li>
 *   <li>{@code text/html} → body content fallback</li>
 *   <li>anything else → {@link Attachment} (filename, base content-type, bytes)</li>
 * </ul>
 *
 * <p>This includes {@code text/calendar}, {@code text/csv}, {@code text/x-vcard},
 * and inline binary parts — any non-plain/non-html MIME type goes to attachments.
 * Observers decide what to do with them; the connector delivers everything present.
 */
final class ContentExtractor {

    private static final Logger LOG = Logger.getLogger(ContentExtractor.class.getName());

    private ContentExtractor() {}

    static ExtractionResult extract(final Part part) {
        final Accumulator acc = new Accumulator();
        traverse(part, acc);
        return new ExtractionResult(acc.resolveContent(), List.copyOf(acc.attachments));
    }

    private static void traverse(final Part part, final Accumulator acc) {
        try {
            if (part.isMimeType("text/plain") && acc.plainText == null) {
                acc.plainText = part.getContent().toString();
            } else if (part.isMimeType("text/html") && acc.htmlText == null) {
                acc.htmlText = part.getContent().toString();
            } else if (part.isMimeType("multipart/*")) {
                final Multipart mp = (Multipart) part.getContent();
                for (int i = 0; i < mp.getCount(); i++) {
                    traverse(mp.getBodyPart(i), acc);
                }
            } else {
                acc.attachments.add(toAttachment(part));
            }
        } catch (final Exception e) {
            LOG.log(Level.WARNING, "email-inbound: content extraction failed on part", e);
        }
    }

    private static Attachment toAttachment(final Part part)
            throws MessagingException, IOException {
        final String filename = part.getFileName(); // null if absent
        String ct = part.getContentType();
        if (ct == null) ct = "application/octet-stream"; // RFC 2045 §5.2 default
        final String baseType = ct.contains(";")
                ? ct.substring(0, ct.indexOf(';')).trim().toLowerCase()
                : ct.trim().toLowerCase();
        final byte[] bytes = part.getInputStream().readAllBytes();
        return new Attachment(filename, baseType, bytes);
    }

    private static final class Accumulator {
        String plainText = null;
        String htmlText  = null;
        final List<Attachment> attachments = new ArrayList<>();

        String resolveContent() {
            if (plainText != null) return plainText;
            if (htmlText  != null) return htmlText;
            return "";
        }
    }
}
