package io.casehub.connectors.email.inbound;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;

/**
 * Recursive MIME content extractor. Prefers {@code text/plain}; falls back to
 * {@code text/html}; returns {@code ""} for binary-only messages.
 *
 * <p>Handles nested {@code multipart/mixed} structures:
 * <pre>
 * multipart/mixed
 *   └── multipart/alternative
 *         ├── text/plain  ← extracted
 *         └── text/html
 *   └── application/pdf   ← ignored
 * </pre>
 */
final class ContentExtractor {

    private static final Logger LOG = Logger.getLogger(ContentExtractor.class.getName());

    private ContentExtractor() {}

    static String extractContent(final Part part) {
        try {
            final String text = extractText(part);
            if (text != null) return text;
            final String html = extractHtml(part);
            if (html != null) return html;
        } catch (final Exception e) {
            LOG.log(Level.WARNING, "email-inbound: content extraction failed", e);
        }
        return "";
    }

    private static String extractText(final Part part) throws MessagingException, IOException {
        if (part.isMimeType("text/plain")) {
            return part.getContent().toString();
        }
        if (part.isMimeType("multipart/*")) {
            final Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                final String result = extractText(mp.getBodyPart(i));
                if (result != null) return result;
            }
        }
        return null;
    }

    private static String extractHtml(final Part part) throws MessagingException, IOException {
        if (part.isMimeType("text/html")) {
            return part.getContent().toString();
        }
        if (part.isMimeType("multipart/*")) {
            final Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                final String result = extractHtml(mp.getBodyPart(i));
                if (result != null) return result;
            }
        }
        return null;
    }
}
