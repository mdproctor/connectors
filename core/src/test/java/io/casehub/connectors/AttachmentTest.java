package io.casehub.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AttachmentTest {

    @Test
    void construction_storesAllFields() {
        final byte[] bytes = {1, 2, 3};
        final Attachment att = new Attachment("report.pdf", "application/pdf", bytes);

        assertThat(att.filename()).isEqualTo("report.pdf");
        assertThat(att.contentType()).isEqualTo("application/pdf");
        assertThat(att.content()).isEqualTo(new byte[]{1, 2, 3});
    }

    @Test
    void nullFilename_isAllowed() {
        final Attachment att = new Attachment(null, "application/octet-stream", new byte[]{});
        assertThat(att.filename()).isNull();
    }

    @Test
    void nullContent_treatedAsEmptyArray() {
        final Attachment att = new Attachment("f.pdf", "application/pdf", null);
        assertThat(att.content()).isEqualTo(new byte[0]);
    }

    @Test
    void content_defensivelyCopiedOnConstruction() {
        final byte[] original = {10, 20, 30};
        final Attachment att = new Attachment("f.bin", "application/octet-stream", original);
        original[0] = 99;
        assertThat(att.content()[0]).isEqualTo((byte) 10); // stored copy unaffected
    }

    @Test
    void content_defensivelyCopiedOnAccess() {
        final Attachment att = new Attachment("f.bin", "application/octet-stream", new byte[]{1, 2});
        final byte[] returned = att.content();
        returned[0] = 99;
        assertThat(att.content()[0]).isEqualTo((byte) 1); // stored copy unaffected
    }

    @Test
    void equals_sameFields_areEqual() {
        final Attachment a = new Attachment("f.pdf", "application/pdf", new byte[]{1, 2});
        final Attachment b = new Attachment("f.pdf", "application/pdf", new byte[]{1, 2});
        assertThat(a).isEqualTo(b);
    }

    @Test
    void equals_differentContent_notEqual() {
        final Attachment a = new Attachment("f.pdf", "application/pdf", new byte[]{1, 2});
        final Attachment b = new Attachment("f.pdf", "application/pdf", new byte[]{3, 4});
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void hashCode_equalAttachments_sameHashCode() {
        final Attachment a = new Attachment("f.pdf", "application/pdf", new byte[]{1, 2});
        final Attachment b = new Attachment("f.pdf", "application/pdf", new byte[]{1, 2});
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void toString_includesByteCount_notRawArray() {
        final Attachment att = new Attachment("report.pdf", "application/pdf", new byte[]{1, 2, 3});
        assertThat(att.toString()).contains("report.pdf").contains("application/pdf").contains("3");
        assertThat(att.toString()).doesNotContain("[B@"); // no default Object.toString() on byte[]
    }
}
