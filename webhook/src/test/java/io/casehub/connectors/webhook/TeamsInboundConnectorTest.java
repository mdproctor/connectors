package io.casehub.connectors.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.connectors.HttpMethod;
import io.casehub.connectors.WebhookRequest;
import io.casehub.connectors.WebhookResult;

class TeamsInboundConnectorTest {

    /** The raw shared secret. The config property stores this as Base64. */
    private static final String RAW_SECRET = "test-teams-secret";

    /** Base64-encoded version stored in config. */
    private static final String BASE64_SECRET =
            Base64.getEncoder().encodeToString(RAW_SECRET.getBytes(StandardCharsets.UTF_8));

    private TeamsInboundConnector connector;

    @BeforeEach
    void setUp() {
        connector = new TeamsInboundConnector();
        connector.sharedSecret = BASE64_SECRET;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static WebhookRequest postRequest(final String body, final String authHeader) {
        return new WebhookRequest(
                body,
                Map.of("authorization", List.of(authHeader)),
                Map.of(),
                HttpMethod.POST,
                "https://test.example.com/connectors/teams-inbound/webhook");
    }

    private static WebhookRequest signedPostRequest(final String body) {
        final byte[] key = Base64.getDecoder().decode(BASE64_SECRET);
        final byte[] hmac = SigHelper.hmac("HmacSHA256",
                key, body.getBytes(StandardCharsets.UTF_8));
        final String authValue = "HMAC " + Base64.getEncoder().encodeToString(hmac);
        return postRequest(body, authValue);
    }

    private static String teamsEvent(final String text) {
        return """
                {"type":"message","text":"%s","from":{"id":"29:user-id"},"channelId":"msteams"}
                """.formatted(text).strip();
    }

    // ── Blank secret ──────────────────────────────────────────────────────────

    @Test
    void blankSecret_returnsIgnored() {
        connector.sharedSecret = "";

        assertThat(connector.handle(signedPostRequest(teamsEvent("hi"))))
                .isInstanceOf(WebhookResult.Ignored.class);
    }

    // ── GET — not used by Teams ───────────────────────────────────────────────

    @Test
    void getRequest_returnsIgnored() {
        final WebhookRequest req = new WebhookRequest(
                "", Map.of(), Map.of(), HttpMethod.GET,
                "https://test.example.com/connectors/teams-inbound/webhook");

        assertThat(connector.handle(req)).isInstanceOf(WebhookResult.Ignored.class);
    }

    // ── Signature verification ────────────────────────────────────────────────

    @Test
    void validSignature_returnsDelivered() {
        final WebhookResult result = connector.handle(signedPostRequest(teamsEvent("hello")));

        assertThat(result).isInstanceOf(WebhookResult.Delivered.class);
    }

    @Test
    void invalidSignature_returnsUnauthorized() {
        final WebhookRequest req = postRequest(teamsEvent("hi"), "HMAC deadbeef");

        assertThat(connector.handle(req)).isInstanceOf(WebhookResult.Unauthorized.class);
    }

    @Test
    void missingAuthHeader_returnsUnauthorized() {
        final WebhookRequest req = new WebhookRequest(
                teamsEvent("hi"), Map.of(), Map.of(), HttpMethod.POST,
                "https://test.example.com/connectors/teams-inbound/webhook");

        assertThat(connector.handle(req)).isInstanceOf(WebhookResult.Unauthorized.class);
    }

    // ── Message parsing ───────────────────────────────────────────────────────

    @Test
    void validEvent_fieldsCorrectlyMapped() {
        final WebhookResult result = connector.handle(signedPostRequest(teamsEvent("Teams message")));

        final WebhookResult.Delivered delivered = (WebhookResult.Delivered) result;
        assertThat(delivered.messages()).hasSize(1);
        assertThat(delivered.messages().get(0).content()).isEqualTo("Teams message");
        assertThat(delivered.messages().get(0).connectorId()).isEqualTo("teams-inbound");
    }

    @Test
    void id_isTeamsInbound() {
        assertThat(connector.id()).isEqualTo("teams-inbound");
    }
}
