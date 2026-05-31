package io.casehub.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class InboundMessageTest {

    @Test
    void sevenArgConstructor_allFieldsSet() {
        final List<Attachment> atts = List.of(
                new Attachment("f.pdf", "application/pdf", new byte[]{1}));
        final Instant now = Instant.now();
        final InboundMessage msg = new InboundMessage(
                "email-inbound", "sender@example.com", "inbox@example.com",
                "body", atts, now, Map.of("k", "v"));

        assertThat(msg.connectorId()).isEqualTo("email-inbound");
        assertThat(msg.externalSenderId()).isEqualTo("sender@example.com");
        assertThat(msg.externalChannelRef()).isEqualTo("inbox@example.com");
        assertThat(msg.content()).isEqualTo("body");
        assertThat(msg.attachments()).hasSize(1);
        assertThat(msg.receivedAt()).isEqualTo(now);
        assertThat(msg.metadata()).containsEntry("k", "v");
    }

    @Test
    void sixArgConvenienceConstructor_attachmentsEmpty() {
        final InboundMessage msg = new InboundMessage(
                "slack-inbound", "U123", "C456", "hello", Instant.now(), Map.of());
        assertThat(msg.attachments()).isEmpty();
    }

    @Test
    void fiveArgConvenienceConstructor_attachmentsEmptyMetadataEmpty() {
        final InboundMessage msg = new InboundMessage(
                "slack-inbound", "U123", "C456", "hello", Instant.now());
        assertThat(msg.attachments()).isEmpty();
        assertThat(msg.metadata()).isEmpty();
    }

    @Test
    void attachments_defensivelyCopied_mutableListCannotAffectRecord() {
        final List<Attachment> mutable = new ArrayList<>();
        mutable.add(new Attachment("f.pdf", "application/pdf", new byte[]{1}));
        final InboundMessage msg = new InboundMessage(
                "email-inbound", "s", "c", "body", mutable, Instant.now(), Map.of());

        mutable.clear(); // mutate after construction
        assertThat(msg.attachments()).hasSize(1); // record unaffected
    }
}
