package io.casehub.connectors.webhook;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
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
 * Inbound connector for the Slack Events API.
 *
 * <h2>Check ordering</h2>
 * <ol>
 * <li>URL verification (before blank-secret guard) — Slack sends this before the
 *     signing secret is configured in the workspace. The challenge itself authenticates.
 * <li>Blank-secret guard → {@link WebhookResult.Ignored}
 * <li>Replay prevention — reject if {@code x-slack-request-timestamp} is &gt; 5 minutes old
 * <li>HMAC-SHA256 signature verification
 * <li>Message parsing and bot-message filtering
 * </ol>
 *
 * <h2>Signature</h2>
 * HMAC-SHA256 of {@code "v0:" + timestamp + ":" + body} using the signing secret.
 * Compared to {@code x-slack-signature} header ({@code "v0=" + hex(hmac)}) using
 * constant-time comparison.
 */
@ApplicationScoped
public class SlackInboundConnector extends WebhookInboundConnector {

    static final String ID = "slack-inbound";

    private static final Logger LOG = Logger.getLogger(SlackInboundConnector.class.getName());
    private static final long REPLAY_WINDOW_SECONDS = 300; // 5 minutes

    @ConfigProperty(name = "casehub.connectors.slack-inbound.signing-secret",
                    defaultValue = "")
    String signingSecret;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public WebhookResult handle(final WebhookRequest request) {
        try {
            return doHandle(request);
        } catch (final Exception e) {
            LOG.severe("Unexpected error in SlackInboundConnector: " + e.getMessage());
            return new WebhookResult.Ignored();
        }
    }

    private WebhookResult doHandle(final WebhookRequest request) {
        // 1. URL verification — must be first, before blank-secret guard
        if (request.method() == HttpMethod.POST && isUrlVerification(request.body())) {
            return buildChallenge(request.body());
        }

        // 2. Blank-secret guard
        if (signingSecret.isBlank()) {
            LOG.warning("slack-inbound: signing-secret not configured — ignoring request");
            return new WebhookResult.Ignored();
        }

        // 3. Replay prevention
        final String timestampHeader = request.header("x-slack-request-timestamp");
        if (timestampHeader == null) {
            return new WebhookResult.Unauthorized();
        }
        try {
            final long ts = Long.parseLong(timestampHeader.trim());
            if (Math.abs(Instant.now().getEpochSecond() - ts) >= REPLAY_WINDOW_SECONDS) {
                LOG.warning("slack-inbound: replayed request rejected (timestamp age > 5 min)");
                return new WebhookResult.Unauthorized();
            }
        } catch (final NumberFormatException e) {
            return new WebhookResult.Unauthorized();
        }

        // 4. HMAC-SHA256 signature
        final String sigHeader = request.header("x-slack-signature");
        if (!verifySignature(request.body(), timestampHeader.trim(), sigHeader)) {
            return new WebhookResult.Unauthorized();
        }

        // 5. Parse and filter
        final List<InboundMessage> messages = parseMessages(request.body());
        return messages.isEmpty()
                ? new WebhookResult.Ignored()
                : new WebhookResult.Delivered(messages);
    }

    private boolean isUrlVerification(final String body) {
        try {
            final JsonObject json = Json.createReader(new StringReader(body)).readObject();
            return "url_verification".equals(json.getString("type", null));
        } catch (final Exception e) {
            return false;
        }
    }

    private WebhookResult buildChallenge(final String body) {
        try {
            final JsonObject json = Json.createReader(new StringReader(body)).readObject();
            final String challenge = json.getString("challenge", "");
            // Use JSON builder — never reflect raw input into JSON string literals
            final String response = Json.createObjectBuilder()
                    .add("challenge", challenge)
                    .build()
                    .toString();
            return new WebhookResult.Challenged(response, "application/json");
        } catch (final Exception e) {
            return new WebhookResult.Ignored();
        }
    }

    private boolean verifySignature(final String body, final String timestamp,
                                    final String sigHeader) {
        if (sigHeader == null || !sigHeader.startsWith("v0=")) return false;
        final String sigBase = "v0:" + timestamp + ":" + body;
        final byte[] hmac = SigHelper.hmac("HmacSHA256",
                signingSecret.getBytes(StandardCharsets.UTF_8),
                sigBase.getBytes(StandardCharsets.UTF_8));
        final byte[] expected = ("v0=" + SigHelper.toHex(hmac))
                .getBytes(StandardCharsets.UTF_8);
        return SigHelper.constantTimeEquals(expected, sigHeader.getBytes(StandardCharsets.UTF_8));
    }

    private List<InboundMessage> parseMessages(final String body) {
        final List<InboundMessage> messages = new ArrayList<>();
        try {
            final JsonObject json = Json.createReader(new StringReader(body)).readObject();
            if (!"event_callback".equals(json.getString("type", null))) return messages;

            final JsonObject event = json.getJsonObject("event");
            if (event == null) return messages;
            if (event.containsKey("bot_id")) return messages; // filter bot messages
            if (!"message".equals(event.getString("type", null))) return messages;

            final String user = event.getString("user", null);
            final String channel = event.getString("channel", null);
            final String text = event.getString("text", "");

            if (user == null || channel == null) return messages;

            messages.add(new InboundMessage(ID, user, channel, text, Instant.now()));
        } catch (final Exception e) {
            LOG.warning("slack-inbound: failed to parse event body: " + e.getMessage());
        }
        return messages;
    }
}
