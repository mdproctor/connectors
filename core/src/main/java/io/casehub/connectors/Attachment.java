package io.casehub.connectors;

import java.util.Arrays;
import java.util.Objects;

/**
 * An attachment extracted from an inbound message.
 *
 * <p>{@code filename} is nullable — MIME does not require a filename.
 * {@code contentType} is the base MIME type, parameters stripped and lowercased
 * (e.g. {@code "application/pdf"}, never {@code "Application/PDF; name=invoice.pdf"}).
 * {@code content} is defensively copied on construction and access.
 *
 * <h2>V1 constraint</h2>
 * Content is fully materialised into a heap-resident {@code byte[]}. Callers
 * processing large files (multi-MB PDFs, scans) must account for heap pressure.
 * Streaming is deferred to a future version.
 */
public record Attachment(String filename, String contentType, byte[] content) {

    public Attachment {
        content = content == null ? new byte[0] : content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof final Attachment other)) return false;
        return Objects.equals(filename, other.filename)
                && Objects.equals(contentType, other.contentType)
                && Arrays.equals(content, other.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filename, contentType, Arrays.hashCode(content));
    }

    @Override
    public String toString() {
        return "Attachment[filename=" + filename
                + ", contentType=" + contentType
                + ", content=" + content.length + " bytes]";
    }
}
