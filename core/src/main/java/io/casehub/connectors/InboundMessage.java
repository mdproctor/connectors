package io.casehub.connectors;

import java.time.Instant;
import java.util.Map;

/**
 * A message received from an external system via an {@link InboundConnector}.
 *
 * <p>Text content only in v1. WhatsApp media messages yield
 * {@code content} = the media URL, or empty string when no URL is available.
 */
public record InboundMessage(
        String connectorId,
        String externalSenderId,
        String externalChannelRef,
        String content,
        Instant receivedAt,
        Map<String, String> metadata) {

    /** Convenience constructor — no metadata. */
    public InboundMessage(final String connectorId,
                          final String externalSenderId,
                          final String externalChannelRef,
                          final String content,
                          final Instant receivedAt) {
        this(connectorId, externalSenderId, externalChannelRef, content, receivedAt, Map.of());
    }
}
