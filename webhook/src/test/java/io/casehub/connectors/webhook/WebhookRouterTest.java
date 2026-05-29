package io.casehub.connectors.webhook;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.connectors.InboundMessage;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class WebhookRouterTest {

    @Inject
    InboundMessageCapture capture;

    @BeforeEach
    void reset() {
        capture.clear();
    }

    // ── Unknown connector ─────────────────────────────────────────────────────

    @Test
    void post_unknownConnectorId_returns404() {
        given()
            .contentType("application/json")
            .body("{}")
            .when().post("/connectors/nonexistent-connector/webhook")
            .then().statusCode(404);
    }

    // ── Slack — POST ──────────────────────────────────────────────────────────

    @Test
    void post_slackValid_returns200AndFiresEvent() throws InterruptedException {
        final String ts = nowTs();
        final String body = slackMessageEvent("U123", "C456", "hello from router test");
        final String sig = slackSig(body, ts, "test-slack-secret");

        given()
            .contentType("application/json")
            .header("X-Slack-Signature", sig)
            .header("X-Slack-Request-Timestamp", ts)
            .body(body)
            .when().post("/connectors/slack-inbound/webhook")
            .then().statusCode(200);

        InboundMessage msg = capture.poll(2, TimeUnit.SECONDS);
        assertThat(msg).isNotNull();
        assertThat(msg.content()).isEqualTo("hello from router test");
    }

    @Test
    void post_slackInvalidSignature_returns200WithNoEvent() throws InterruptedException {
        given()
            .contentType("application/json")
            .header("X-Slack-Signature", "v0=badhash")
            .header("X-Slack-Request-Timestamp", nowTs())
            .body(slackMessageEvent("U1", "C1", "hi"))
            .when().post("/connectors/slack-inbound/webhook")
            .then().statusCode(200);  // 200 to suppress retries

        InboundMessage msg = capture.poll(200, TimeUnit.MILLISECONDS);
        assertThat(msg).isNull();
    }

    @Test
    void post_slackUrlVerification_returnsChallengeJson() {
        final String body = "{\"type\":\"url_verification\",\"challenge\":\"test-tok\"}";

        given()
            .contentType("application/json")
            .body(body)
            .when().post("/connectors/slack-inbound/webhook")
            .then()
            .statusCode(200)
            .body(equalTo("{\"challenge\":\"test-tok\"}"));
    }

    @Test
    void post_slackExceptionInConnector_returns200() {
        // Malformed JSON — connector will catch internally and return Ignored
        given()
            .contentType("application/json")
            .header("X-Slack-Signature", "v0=whatever")
            .header("X-Slack-Request-Timestamp", nowTs())
            .body("NOT JSON AT ALL !!!!")
            .when().post("/connectors/slack-inbound/webhook")
            .then().statusCode(200);
    }

    // ── WhatsApp — GET challenge ──────────────────────────────────────────────

    @Test
    void get_whatsappValidChallenge_returns200WithToken() {
        given()
            .queryParam("hub.mode", "subscribe")
            .queryParam("hub.verify_token", "test-verify-token")
            .queryParam("hub.challenge", "challenge-abc")
            .when().get("/connectors/whatsapp-inbound/webhook")
            .then()
            .statusCode(200)
            .body(equalTo("challenge-abc"));
    }

    @Test
    void get_whatsappWrongVerifyToken_returns403() {
        given()
            .queryParam("hub.mode", "subscribe")
            .queryParam("hub.verify_token", "wrong-token")
            .queryParam("hub.challenge", "abc")
            .when().get("/connectors/whatsapp-inbound/webhook")
            .then().statusCode(403);  // admin console needs clear failure signal
    }

    // ── Twilio — form-encoded POST ────────────────────────────────────────────

    @Test
    void post_twilioValidSms_returns200AndFiresEvent() throws InterruptedException {
        final TreeMap<String, String> params = new TreeMap<>();
        params.put("From", "+447700900001");
        params.put("To", "+447700900002");
        params.put("Body", "test sms");
        params.put("MessageSid", "SM999");

        final String url = "http://localhost:" + io.restassured.RestAssured.port
                + "/connectors/twilio-sms-inbound/webhook";
        final String sig = twilioSig(url, params, "test-twilio-token");
        final String formBody = buildFormBody(params);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("X-Twilio-Signature", sig)
            .body(formBody)
            .when().post("/connectors/twilio-sms-inbound/webhook")
            .then().statusCode(200);

        InboundMessage msg = capture.poll(2, TimeUnit.SECONDS);
        assertThat(msg).isNotNull();
        assertThat(msg.externalSenderId()).isEqualTo("+447700900001");
        assertThat(msg.content()).isEqualTo("test sms");
    }

    // ── WhatsApp — POST ───────────────────────────────────────────────────────

    @Test
    void post_whatsappValidTextMessage_returns200AndFiresEvent() throws InterruptedException {
        final String body = """
                {"entry":[{"changes":[{"value":{"messages":[{"from":"15551234","type":"text","text":{"body":"whatsapp hello"}}],"metadata":{"phone_number_id":"15559999"}}}]}]}
                """.strip();
        final byte[] hmac = SigHelper.hmac("HmacSHA256",
                "test-whatsapp-secret".getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8));
        final String sig = "sha256=" + HexFormat.of().formatHex(hmac);

        given()
            .contentType("application/json")
            .header("X-Hub-Signature-256", sig)
            .body(body)
            .when().post("/connectors/whatsapp-inbound/webhook")
            .then().statusCode(200);

        InboundMessage msg = capture.poll(2, TimeUnit.SECONDS);
        assertThat(msg).isNotNull();
        assertThat(msg.externalSenderId()).isEqualTo("15551234");
        assertThat(msg.content()).isEqualTo("whatsapp hello");
    }

    // ── Teams — GET not supported ─────────────────────────────────────────────

    @Test
    void get_teamsConnector_returns200Ignored() throws InterruptedException {
        given()
            .when().get("/connectors/teams-inbound/webhook")
            .then().statusCode(200);  // Ignored → 200

        InboundMessage msg = capture.poll(200, TimeUnit.MILLISECONDS);
        assertThat(msg).isNull();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String nowTs() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    private static String slackMessageEvent(final String user, final String channel,
                                            final String text) {
        return """
                {"type":"event_callback","event":{"type":"message","user":"%s","channel":"%s","text":"%s"}}
                """.formatted(user, channel, text).strip();
    }

    private static String slackSig(final String body, final String ts, final String secret) {
        final String sigBase = "v0:" + ts + ":" + body;
        final byte[] hmac = SigHelper.hmac("HmacSHA256",
                secret.getBytes(StandardCharsets.UTF_8),
                sigBase.getBytes(StandardCharsets.UTF_8));
        return "v0=" + SigHelper.toHex(hmac);
    }

    private static String twilioSig(final String url, final TreeMap<String, String> params,
                                    final String token) {
        final StringBuilder toSign = new StringBuilder(url);
        params.forEach((k, v) -> toSign.append(k).append(v));
        final byte[] hmac = SigHelper.hmac("HmacSHA1",
                token.getBytes(StandardCharsets.UTF_8),
                toSign.toString().getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hmac);
    }

    private static String buildFormBody(final TreeMap<String, String> params) {
        final StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (!sb.isEmpty()) sb.append("&");
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
              .append("=")
              .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }
}
