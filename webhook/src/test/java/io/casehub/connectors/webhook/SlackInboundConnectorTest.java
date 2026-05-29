package io.casehub.connectors.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.connectors.HttpMethod;
import io.casehub.connectors.WebhookRequest;
import io.casehub.connectors.WebhookResult;

class SlackInboundConnectorTest {

    private SlackInboundConnector connector;

    @BeforeEach
    void setUp() {
        connector = new SlackInboundConnector();
        connector.signingSecret = "test-slack-secret";
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String nowTs() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    private static WebhookRequest postRequest(final String body, final String ts,
                                              final String secret) {
        final byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        final String sigBase = "v0:" + ts + ":" + body;
        final byte[] hmac = SigHelper.hmac("HmacSHA256",
                key, sigBase.getBytes(StandardCharsets.UTF_8));
        final String sig = "v0=" + SigHelper.toHex(hmac);

        return new WebhookRequest(
                body,
                Map.of(
                        "x-slack-signature", List.of(sig),
                        "x-slack-request-timestamp", List.of(ts)),
                Map.of(),
                HttpMethod.POST,
                "https://test.example.com/connectors/slack-inbound/webhook");
    }

    private static WebhookRequest postRequest(final String body) {
        return postRequest(body, nowTs(), "test-slack-secret");
    }

    private static String messageEvent(final String user, final String channel,
                                       final String text) {
        return """
                {"type":"event_callback","event":{"type":"message","user":"%s","channel":"%s","text":"%s"}}
                """.formatted(user, channel, text).strip();
    }

    private static String urlVerification(final String challenge) {
        return """
                {"type":"url_verification","challenge":"%s"}
                """.formatted(challenge).strip();
    }

    private static String botMessage() {
        return """
                {"type":"event_callback","event":{"type":"message","bot_id":"B123","text":"from bot"}}
                """.strip();
    }

    private static String appMentionEvent() {
        return """
                {"type":"event_callback","event":{"type":"app_mention","user":"U123","text":"hey"}}
                """.strip();
    }

    // ── Blank secret ─────────────────────────────────────────────────────────

    @Test
    void blankSecret_returnsIgnored() {
        connector.signingSecret = "";

        final WebhookResult result = connector.handle(postRequest(messageEvent("U1", "C1", "hi")));

        assertThat(result).isInstanceOf(WebhookResult.Ignored.class);
    }

    // ── URL verification (no sig check — before secret guard) ───────────────

    @Test
    void urlVerification_returnsChallenge_withoutSigCheck() {
        final String body = urlVerification("my-challenge-token");
        // Build request WITHOUT a valid signature — should still succeed
        final WebhookRequest req = new WebhookRequest(
                body, Map.of(), Map.of(), HttpMethod.POST,
                "https://test.example.com/connectors/slack-inbound/webhook");

        final WebhookResult result = connector.handle(req);

        assertThat(result).isInstanceOf(WebhookResult.Challenged.class);
        final WebhookResult.Challenged challenged = (WebhookResult.Challenged) result;
        assertThat(challenged.responseBody()).contains("my-challenge-token");
        assertThat(challenged.contentType()).isEqualTo("application/json");
    }

    @Test
    void urlVerification_blankSecret_stillReturnsChallenge() {
        connector.signingSecret = "";
        final String body = urlVerification("tok");
        final WebhookRequest req = new WebhookRequest(
                body, Map.of(), Map.of(), HttpMethod.POST,
                "https://test.example.com/connectors/slack-inbound/webhook");

        assertThat(connector.handle(req)).isInstanceOf(WebhookResult.Challenged.class);
    }

    // ── Replay prevention ────────────────────────────────────────────────────

    @Test
    void staleTimestamp_returnsUnauthorized() {
        final String staleTs = String.valueOf(Instant.now().getEpochSecond() - 400); // 6+ min ago
        final String body = messageEvent("U1", "C1", "hi");
        final WebhookRequest req = postRequest(body, staleTs, "test-slack-secret");

        assertThat(connector.handle(req)).isInstanceOf(WebhookResult.Unauthorized.class);
    }

    @Test
    void timestampAtExactlyWindowBoundary_returnsUnauthorized() {
        // 300s = exactly at window — must be rejected (>= 300 is stale per Slack docs)
        final String ts = String.valueOf(Instant.now().getEpochSecond() - 300);
        final String body = messageEvent("U1", "C1", "hi");
        assertThat(connector.handle(postRequest(body, ts, "test-slack-secret")))
                .isInstanceOf(WebhookResult.Unauthorized.class);
    }

    @Test
    void timestampJustInsideWindow_passes() {
        // 299s = just inside the window — must be accepted
        final String ts = String.valueOf(Instant.now().getEpochSecond() - 299);
        final String body = messageEvent("U1", "C1", "hi");
        assertThat(connector.handle(postRequest(body, ts, "test-slack-secret")))
                .isInstanceOf(WebhookResult.Delivered.class);
    }

    @Test
    void freshTimestamp_passes() {
        final WebhookResult result = connector.handle(postRequest(messageEvent("U1", "C1", "hi")));
        assertThat(result).isInstanceOf(WebhookResult.Delivered.class);
    }

    // ── Signature verification ────────────────────────────────────────────────

    @Test
    void invalidSignature_returnsUnauthorized() {
        final String body = messageEvent("U1", "C1", "hi");
        final WebhookRequest req = new WebhookRequest(
                body,
                Map.of(
                        "x-slack-signature", List.of("v0=deadbeef"),
                        "x-slack-request-timestamp", List.of(nowTs())),
                Map.of(),
                HttpMethod.POST,
                "https://test.example.com/connectors/slack-inbound/webhook");

        assertThat(connector.handle(req)).isInstanceOf(WebhookResult.Unauthorized.class);
    }

    @Test
    void wrongSecret_returnsUnauthorized() {
        final String body = messageEvent("U1", "C1", "hi");
        final WebhookRequest req = postRequest(body, nowTs(), "wrong-secret");

        assertThat(connector.handle(req)).isInstanceOf(WebhookResult.Unauthorized.class);
    }

    // ── Message parsing ───────────────────────────────────────────────────────

    @Test
    void validMessageEvent_returnsDelivered() {
        final WebhookResult result = connector.handle(
                postRequest(messageEvent("U123", "C456", "hello world")));

        assertThat(result).isInstanceOf(WebhookResult.Delivered.class);
        final WebhookResult.Delivered delivered = (WebhookResult.Delivered) result;
        assertThat(delivered.messages()).hasSize(1);
        assertThat(delivered.messages().get(0).externalSenderId()).isEqualTo("U123");
        assertThat(delivered.messages().get(0).externalChannelRef()).isEqualTo("C456");
        assertThat(delivered.messages().get(0).content()).isEqualTo("hello world");
        assertThat(delivered.messages().get(0).connectorId()).isEqualTo("slack-inbound");
    }

    @Test
    void botMessage_returnsIgnored() {
        assertThat(connector.handle(postRequest(botMessage())))
                .isInstanceOf(WebhookResult.Ignored.class);
    }

    @Test
    void nonMessageEventType_returnsIgnored() {
        assertThat(connector.handle(postRequest(appMentionEvent())))
                .isInstanceOf(WebhookResult.Ignored.class);
    }

    @Test
    void id_isSlackInbound() {
        assertThat(connector.id()).isEqualTo("slack-inbound");
    }
}
