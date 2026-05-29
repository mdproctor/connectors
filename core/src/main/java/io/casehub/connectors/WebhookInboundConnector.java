package io.casehub.connectors;

/**
 * Base class for webhook-based inbound connectors (Slack, Teams, WhatsApp, Twilio SMS).
 *
 * <p>This class does <strong>not</strong> implement {@link InboundConnector}. Webhook
 * connectors have no pull lifecycle — their lifecycle is JAX-RS. They are discovered
 * by {@code WebhookRouter} via {@code @All List<WebhookInboundConnector>} and by
 * {@code InboundConnectorService} only when the latter also needs to manage pull connectors
 * in the same deployment.
 *
 * <h2>ID contract</h2>
 * {@code id()} must be lowercase, URL-safe, no slashes or spaces (pattern:
 * {@code [a-z0-9][a-z0-9\-]*}). It is also the URL path segment:
 * {@code POST /connectors/{id}/webhook}. Validated at startup by {@code WebhookRouter}.
 *
 * <h2>Exception safety</h2>
 * {@code handle()} must not throw. Catch all exceptions internally and return
 * {@link WebhookResult.Ignored} or {@link WebhookResult.Unauthorized} on error.
 * The router wraps {@code handle()} in a try-catch as defense-in-depth, but that
 * catch is a last resort — not a substitute for connector-level error handling.
 */
public abstract class WebhookInboundConnector {

    /**
     * Unique identifier, also the URL path segment.
     * Examples: {@code "slack-inbound"}, {@code "whatsapp-inbound"}.
     */
    public abstract String id();

    /**
     * Handle an inbound HTTP request (GET or POST).
     *
     * <p>Must not throw — catch exceptions internally and return an appropriate result.
     *
     * @param request the normalised inbound request
     * @return the action the router should take
     */
    public abstract WebhookResult handle(WebhookRequest request);
}
