package io.casehub.connectors.webhook;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.casehub.connectors.HttpMethod;
import io.casehub.connectors.InboundMessage;
import io.casehub.connectors.WebhookInboundConnector;
import io.casehub.connectors.WebhookRequest;
import io.casehub.connectors.WebhookResult;

/**
 * Inbound connector for Microsoft Teams Outgoing Webhooks.
 *
 * <p>Integration model: <b>Teams Outgoing Webhooks</b> (not Bot Framework / Azure AD OAuth).
 * Set up via Teams &rarr; Channel &rarr; Connectors &rarr; Outgoing Webhook. The shared
 * secret is displayed Base64-encoded; store it as-is in config.
 *
 * <h2>Signature algorithm</h2>
 * <ol>
 * <li>Base64-decode the shared secret from config
 * <li>Compute HMAC-SHA256 of the raw UTF-8 body bytes using the decoded key
 * <li>Base64-encode the HMAC result
 * <li>Compare to the {@code Authorization: HMAC <base64>} header value (constant-time)
 * </ol>
 *
 * <p>Teams Outgoing Webhooks do not use GET challenges. GET requests return
 * {@link WebhookResult.Ignored}.
 */
@ApplicationScoped
public class TeamsInboundConnector extends WebhookInboundConnector {

    static final String ID = "teams-inbound";

    private static final Logger LOG = Logger.getLogger(TeamsInboundConnector.class.getName());

    @ConfigProperty(name = "casehub.connectors.teams-inbound.shared-secret",
                    defaultValue = "")
    String sharedSecret;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public WebhookResult handle(final WebhookRequest request) {
        try {
            return doHandle(request);
        } catch (final Exception e) {
            LOG.severe("Unexpected error in TeamsInboundConnector: " + e.getMessage());
            return new WebhookResult.Ignored();
        }
    }

    private WebhookResult doHandle(final WebhookRequest request) {
        if (request.method() == HttpMethod.GET) {
            return new WebhookResult.Ignored();
        }

        if (sharedSecret.isBlank()) {
            LOG.warning("teams-inbound: shared-secret not configured — ignoring request");
            return new WebhookResult.Ignored();
        }

        if (!verifySignature(request.body(), request.header("authorization"))) {
            return new WebhookResult.Unauthorized();
        }

        return parseMessage(request.body());
    }

    private boolean verifySignature(final String body, final String authHeader) {
        if (authHeader == null || !authHeader.startsWith("HMAC ")) return false;
        try {
            final byte[] key = Base64.getDecoder().decode(sharedSecret.trim());
            final byte[] hmac = SigHelper.hmac("HmacSHA256",
                    key, body.getBytes(StandardCharsets.UTF_8));
            final byte[] expected = Base64.getEncoder().encodeToString(hmac)
                    .getBytes(StandardCharsets.UTF_8);
            final byte[] actual = authHeader.substring("HMAC ".length()).trim()
                    .getBytes(StandardCharsets.UTF_8);
            return SigHelper.constantTimeEquals(expected, actual);
        } catch (final Exception e) {
            LOG.warning("teams-inbound: signature verification failed: " + e.getMessage());
            return false;
        }
    }

    private WebhookResult parseMessage(final String body) {
        try {
            final JsonObject json = Json.createReader(new StringReader(body)).readObject();
            final String text = json.getString("text", "");
            final JsonObject from = json.getJsonObject("from");
            final String senderId = (from != null) ? from.getString("id", "unknown") : "unknown";
            final String channelId = json.getString("channelId", "unknown");
            return new WebhookResult.Delivered(
                    List.of(new InboundMessage(ID, senderId, channelId, text, Instant.now())));
        } catch (final Exception e) {
            LOG.warning("teams-inbound: failed to parse event body: " + e.getMessage());
            return new WebhookResult.Ignored();
        }
    }
}
