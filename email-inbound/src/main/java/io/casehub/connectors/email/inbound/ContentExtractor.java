package io.casehub.connectors.email.inbound;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

        // Jakarta Activation's DataContentHandler (DCH) has no registered handler for
        // many MIME types (application/pdf, image/png, text/calendar, etc.) in a plain
        // Jakarta Mail environment, causing getInputStream() to throw
        // UnsupportedDataTypeException when content was constructed in-memory.
        //
        // Real IMAP messages arrive transfer-encoded (base64); angus-mail decodes them
        // before the DataHandler sees them, so getInputStream() works correctly there.
        //
        // Strategy: call getContent() first — it returns the raw Java object that the
        // part stores without going through the DCH stream path:
        //   byte[]     — binary content set via setContent(byte[], mimeType)
        //   InputStream — transfer-decoded stream from a real IMAP message
        //   String     — text content set via setContent(String, mimeType)
        //   other      — fall back to getInputStream() (real IMAP, handler available)
        final byte[] bytes;
        final Object raw = part.getContent();
        if (raw instanceof byte[] directBytes) {
            bytes = directBytes;
        } else if (raw instanceof InputStream is) {
            bytes = is.readAllBytes();
        } else if (raw instanceof String s) {
            // Text attachments (text/calendar, text/csv, etc.) stored as String in-memory.
            final String charset = extractCharset(ct);
            bytes = s.getBytes(Charset.forName(charset));
        } else {
            // Real IMAP decoded content with an available DCH.
            bytes = part.getInputStream().readAllBytes();
        }
        return new Attachment(filename, baseType, bytes);
    }

    private static final Pattern CHARSET_PARAM =
            Pattern.compile("(?i)charset\\s*=\\s*([\\w-]+)");

    /** Extract charset from a Content-Type header value; default UTF-8. */
    private static String extractCharset(final String contentType) {
        if (contentType != null) {
            final Matcher m = CHARSET_PARAM.matcher(contentType);
            if (m.find()) return m.group(1);
        }
        return "UTF-8";
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
