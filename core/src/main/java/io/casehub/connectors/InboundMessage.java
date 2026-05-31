package io.casehub.connectors;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A message received from an external system via an {@link InboundConnector}.
 *
 * <p>{@code attachments} is always non-null; it is {@code List.of()} for connectors
 * that produce no attachments (Slack, Teams, SMS, WhatsApp). Email inbound
 * populates it from the MIME structure.
 *
 * <p>{@code metadata["attachment-count"]} is always present for email-inbound messages
 * (even when zero), allowing observers to branch without touching binary content.
 */
public record InboundMessage(
        String connectorId,
        String externalSenderId,
        String externalChannelRef,
        String content,
        List<Attachment> attachments,
        Instant receivedAt,
        Map<String, String> metadata) {

    public InboundMessage {
        attachments = List.copyOf(attachments);
    }

    /** No attachments, with metadata. Preserves all existing webhook connector call sites. */
    public InboundMessage(final String connectorId,
                          final String externalSenderId,
                          final String externalChannelRef,
                          final String content,
                          final Instant receivedAt,
                          final Map<String, String> metadata) {
        this(connectorId, externalSenderId, externalChannelRef, content,
                List.of(), receivedAt, metadata);
    }

    /** No attachments, no metadata. */
    public InboundMessage(final String connectorId,
                          final String externalSenderId,
                          final String externalChannelRef,
                          final String content,
                          final Instant receivedAt) {
        this(connectorId, externalSenderId, externalChannelRef, content,
                List.of(), receivedAt, Map.of());
    }
}
