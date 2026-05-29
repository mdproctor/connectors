package io.casehub.connectors;

import java.util.List;

/**
 * Result returned by {@link WebhookInboundConnector#handle(WebhookRequest)}.
 *
 * <p>The router pattern-matches exhaustively on this sealed type, making it
 * impossible to add a new result variant without updating the router.
 *
 * <h2>HTTP status mapping (applied by WebhookRouter)</h2>
 * <ul>
 * <li>{@code Delivered} → 200 OK (after firing CDI events)
 * <li>{@code Challenged} → 200 OK with the challenge body
 * <li>{@code Ignored} → 200 OK (no event fired)
 * <li>{@code Unauthorized} from POST → 200 OK + SECURITY WARNING log
 *     (suppress automated platform retries — Slack retries for up to 30 days on non-2xx)
 * <li>{@code Unauthorized} from GET → 403 Forbidden
 *     (admin console setup failure — the admin needs a clear failure signal)
 * </ul>
 */
public sealed interface WebhookResult {

    /**
     * One or more messages were successfully parsed and should be delivered.
     *
     * <p>A list handles platforms that batch multiple events per webhook call.
     */
    record Delivered(List<InboundMessage> messages) implements WebhookResult {}

    /**
     * A platform challenge/verification handshake — respond with {@code responseBody}.
     *
     * <p>{@code contentType} is included because Slack expects {@code application/json}
     * while WhatsApp expects {@code text/plain}.
     */
    record Challenged(String responseBody, String contentType) implements WebhookResult {}

    /**
     * The request was received but should not produce an event.
     * Used for bot messages, unsupported event types, or unconfigured connectors.
     */
    record Ignored() implements WebhookResult {}

    /**
     * The request failed authentication (invalid signature, replay, wrong token).
     *
     * <p>POST → HTTP 200 + SECURITY log (suppress retry storms).
     * GET  → HTTP 403 (admin console setup — human needs a clear failure signal).
     */
    record Unauthorized() implements WebhookResult {}
}
