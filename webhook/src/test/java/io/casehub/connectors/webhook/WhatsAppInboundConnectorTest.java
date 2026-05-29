package io.casehub.connectors.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.connectors.HttpMethod;
import io.casehub.connectors.WebhookRequest;
import io.casehub.connectors.WebhookResult;

class WhatsAppInboundConnectorTest {

    private WhatsAppInboundConnector connector;

    @BeforeEach
    void setUp() {
        connector = new WhatsAppInboundConnector();
        connector.appSecret = "test-whatsapp-secret";
        connector.verifyToken = "test-verify-token";
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static WebhookRequest signedPostRequest(final String body) {
        final byte[] key = "test-whatsapp-secret".getBytes(StandardCharsets.UTF_8);
        final byte[] hmac = SigHelper.hmac("HmacSHA256",
                key, body.getBytes(StandardCharsets.UTF_8));
        final String sigHeader = "sha256=" + HexFormat.of().formatHex(hmac);
        return new WebhookRequest(
                body,
                Map.of("x-hub-signature-256", List.of(sigHeader)),
                Map.of(),
                HttpMethod.POST,
                "https://test.example.com/connectors/whatsapp-inbound/webhook");
    }

    private static WebhookRequest getChallenge(final String mode, final String token,
                                               final String challenge) {
        return new WebhookRequest(
                "",
                Map.of(),
                Map.of("hub.mode", mode, "hub.verify_token", token,
                        "hub.challenge", challenge),
                HttpMethod.GET,
                "https://test.example.com/connectors/whatsapp-inbound/webhook");
    }

    private static String textMessage(final String from, final String to, final String text) {
        return """
                {"entry":[{"changes":[{"value":{"messages":[{"id":"wamid.TEST123","from":"%s","type":"text","text":{"body":"%s"}}],"metadata":{"phone_number_id":"%s"}}}]}]}
                """.formatted(from, text, to).strip();
    }

    private static String mediaMessage(final String mediaUrl) {
        return """
                {"entry":[{"changes":[{"value":{"messages":[{"from":"15551234","type":"image","image":{"url":"%s"}}],"metadata":{"phone_number_id":"15559999"}}}]}]}
                """.formatted(mediaUrl).strip();
    }

    // ── Blank secret / token ──────────────────────────────────────────────────

    @Test
    void blankAppSecret_returnsIgnored() {
        connector.appSecret = "";

        assertThat(connector.handle(signedPostRequest(textMessage("15551234", "15559999", "hi"))))
                .isInstanceOf(WebhookResult.Ignored.class);
    }

    // ── GET challenge ─────────────────────────────────────────────────────────

    @Test
    void validGetChallenge_returnsChallengedWithToken() {
        final WebhookRequest req = getChallenge("subscribe", "test-verify-token", "abc123");

        final WebhookResult result = connector.handle(req);

        assertThat(result).isInstanceOf(WebhookResult.Challenged.class);
        final WebhookResult.Challenged challenged = (WebhookResult.Challenged) result;
        assertThat(challenged.responseBody()).isEqualTo("abc123");
        assertThat(challenged.contentType()).isEqualTo("text/plain");
    }

    @Test
    void getChallenge_wrongToken_returnsUnauthorized() {
        final WebhookRequest req = getChallenge("subscribe", "wrong-token", "abc123");

        assertThat(connector.handle(req)).isInstanceOf(WebhookResult.Unauthorized.class);
    }

    @Test
    void getChallenge_wrongMode_returnsUnauthorized() {
        final WebhookRequest req = getChallenge("unsubscribe", "test-verify-token", "abc123");

        assertThat(connector.handle(req)).isInstanceOf(WebhookResult.Unauthorized.class);
    }

    // ── POST signature ────────────────────────────────────────────────────────

    @Test
    void validPostSignature_textMessage_returnsDelivered() {
        final WebhookResult result = connector.handle(
                signedPostRequest(textMessage("15551234", "15559999", "hello")));

        assertThat(result).isInstanceOf(WebhookResult.Delivered.class);
        final WebhookResult.Delivered delivered = (WebhookResult.Delivered) result;
        assertThat(delivered.messages()).hasSize(1);
        assertThat(delivered.messages().get(0).externalSenderId()).isEqualTo("15551234");
        assertThat(delivered.messages().get(0).content()).isEqualTo("hello");
        assertThat(delivered.messages().get(0).connectorId()).isEqualTo("whatsapp-inbound");
        assertThat(delivered.messages().get(0).metadata()).containsEntry("message-id", "wamid.TEST123");
    }

    @Test
    void invalidPostSignature_returnsUnauthorized() {
        final WebhookRequest req = new WebhookRequest(
                textMessage("15551234", "15559999", "hi"),
                Map.of("x-hub-signature-256", List.of("sha256=deadbeef")),
                Map.of(),
                HttpMethod.POST,
                "https://test.example.com/connectors/whatsapp-inbound/webhook");

        assertThat(connector.handle(req)).isInstanceOf(WebhookResult.Unauthorized.class);
    }

    @Test
    void mediaMessage_contentIsMediaUrl() {
        final WebhookResult result = connector.handle(
                signedPostRequest(mediaMessage("https://example.com/img.jpg")));

        assertThat(result).isInstanceOf(WebhookResult.Delivered.class);
        final WebhookResult.Delivered delivered = (WebhookResult.Delivered) result;
        assertThat(delivered.messages().get(0).content()).isEqualTo("https://example.com/img.jpg");
        // mediaMessage fixture has no message id — metadata["message-id"] absent
        assertThat(delivered.messages().get(0).metadata()).doesNotContainKey("message-id");
    }

    @Test
    void id_isWhatsappInbound() {
        assertThat(connector.id()).isEqualTo("whatsapp-inbound");
    }
}
