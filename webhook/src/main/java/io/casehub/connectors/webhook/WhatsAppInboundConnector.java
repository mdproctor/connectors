package io.casehub.connectors.webhook;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.casehub.connectors.HttpMethod;
import io.casehub.connectors.InboundMessage;
import io.casehub.connectors.WebhookInboundConnector;
import io.casehub.connectors.WebhookRequest;
import io.casehub.connectors.WebhookResult;

/**
 * Inbound connector for WhatsApp Business API (Meta Cloud API webhooks).
 *
 * <h2>GET challenge (subscription verification)</h2>
 * Meta sends a GET with {@code hub.mode=subscribe}, {@code hub.verify_token}, and
 * {@code hub.challenge}. If the verify token matches, respond with the challenge value
 * ({@code text/plain}). Wrong token → {@link WebhookResult.Unauthorized} (mapped to
 * HTTP 403 by the router so the admin console shows a clear failure).
 *
 * <h2>POST signature</h2>
 * HMAC-SHA256 of the raw body using the app secret. Compared to
 * {@code X-Hub-Signature-256: sha256=<hex>} using constant-time comparison.
 *
 * <h2>Media messages</h2>
 * Text messages set {@code content} to the message body. Media messages (image, audio,
 * document, sticker) set {@code content} to the media URL if available, or empty string
 * (v1 limitation — binary media is out of scope).
 */
@ApplicationScoped
public class WhatsAppInboundConnector extends WebhookInboundConnector {

    static final String ID = "whatsapp-inbound";

    private static final Logger LOG = Logger.getLogger(WhatsAppInboundConnector.class.getName());

    @ConfigProperty(name = "casehub.connectors.whatsapp-inbound.app-secret", defaultValue = "")
    String appSecret;

    @ConfigProperty(name = "casehub.connectors.whatsapp-inbound.verify-token", defaultValue = "")
    String verifyToken;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public WebhookResult handle(final WebhookRequest request) {
        try {
            return doHandle(request);
        } catch (final Exception e) {
            LOG.severe("Unexpected error in WhatsAppInboundConnector: " + e.getMessage());
            return new WebhookResult.Ignored();
        }
    }

    private WebhookResult doHandle(final WebhookRequest request) {
        if (request.method() == HttpMethod.GET) {
            return handleGetChallenge(request);
        }

        if (appSecret.isBlank()) {
            LOG.warning("whatsapp-inbound: app-secret not configured — ignoring request");
            return new WebhookResult.Ignored();
        }

        if (!verifySignature(request.body(), request.header("x-hub-signature-256"))) {
            return new WebhookResult.Unauthorized();
        }

        final List<InboundMessage> messages = parseMessages(request.body());
        return messages.isEmpty()
                ? new WebhookResult.Ignored()
                : new WebhookResult.Delivered(messages);
    }

    private WebhookResult handleGetChallenge(final WebhookRequest request) {
        final String mode = request.queryParams().get("hub.mode");
        final String token = request.queryParams().get("hub.verify_token");
        final String challenge = request.queryParams().get("hub.challenge");

        // Constant-time token comparison — consistent with the security model for HMAC paths.
        // Note: MessageDigest.isEqual leaks the length of verifyToken, which is acceptable
        // given this is a human-initiated admin action (not an automated polling loop).
        final byte[] expected = verifyToken.getBytes(StandardCharsets.UTF_8);
        final byte[] actual = (token == null ? "" : token).getBytes(StandardCharsets.UTF_8);

        if ("subscribe".equals(mode) && SigHelper.constantTimeEquals(expected, actual)
                && challenge != null) {
            return new WebhookResult.Challenged(challenge, "text/plain");
        }
        return new WebhookResult.Unauthorized();
    }

    private boolean verifySignature(final String body, final String sigHeader) {
        if (sigHeader == null || !sigHeader.startsWith("sha256=")) return false;
        final byte[] hmac = SigHelper.hmac("HmacSHA256",
                appSecret.getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8));
        final byte[] expected = ("sha256=" + SigHelper.toHex(hmac))
                .getBytes(StandardCharsets.UTF_8);
        return SigHelper.constantTimeEquals(expected, sigHeader.getBytes(StandardCharsets.UTF_8));
    }

    private List<InboundMessage> parseMessages(final String body) {
        final List<InboundMessage> messages = new ArrayList<>();
        try {
            final JsonObject root = Json.createReader(new StringReader(body)).readObject();
            final JsonArray entries = root.getJsonArray("entry");
            if (entries == null) return messages;

            for (final var entry : entries) {
                final JsonArray changes = ((JsonObject) entry).getJsonArray("changes");
                if (changes == null) continue;
                for (final var change : changes) {
                    final JsonObject value = ((JsonObject) change).getJsonObject("value");
                    if (value == null) continue;
                    extractMessages(value, messages);
                }
            }
        } catch (final Exception e) {
            LOG.warning("whatsapp-inbound: failed to parse event body: " + e.getMessage());
        }
        return messages;
    }

    private void extractMessages(final JsonObject value, final List<InboundMessage> out) {
        final JsonArray msgs = value.getJsonArray("messages");
        if (msgs == null) return;
        final JsonObject metadata = value.getJsonObject("metadata");
        final String phoneNumberId = (metadata != null)
                ? metadata.getString("phone_number_id", "unknown") : "unknown";

        for (final var m : msgs) {
            final JsonObject msg = (JsonObject) m;
            final String from = msg.getString("from", "unknown");
            final String type = msg.getString("type", "text");
            final String content = extractContent(msg, type);
            out.add(new InboundMessage(ID, from, phoneNumberId, content, Instant.now()));
        }
    }

    private String extractContent(final JsonObject msg, final String type) {
        if ("text".equals(type)) {
            final JsonObject text = msg.getJsonObject("text");
            return (text != null) ? text.getString("body", "") : "";
        }
        // Media messages — return URL if present, empty string otherwise (v1 limitation)
        for (final String mediaType : List.of("image", "audio", "document", "video", "sticker")) {
            if (mediaType.equals(type)) {
                final JsonObject media = msg.getJsonObject(mediaType);
                if (media != null) {
                    final jakarta.json.JsonValue urlVal = media.get("url");
                    if (urlVal instanceof JsonString) return ((JsonString) urlVal).getString();
                }
                return "";
            }
        }
        return "";
    }
}
