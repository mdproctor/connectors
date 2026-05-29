package io.casehub.connectors.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.connectors.HttpMethod;
import io.casehub.connectors.WebhookRequest;
import io.casehub.connectors.WebhookResult;

class TwilioSmsInboundConnectorTest {

    private static final String TEST_URL =
            "https://test.example.com/connectors/twilio-sms-inbound/webhook";
    private static final String AUTH_TOKEN = "test-twilio-token";

    private TwilioSmsInboundConnector connector;

    @BeforeEach
    void setUp() {
        connector = new TwilioSmsInboundConnector();
        connector.authToken = AUTH_TOKEN;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Build a form-encoded body from params and compute the Twilio signature.
     * Twilio signs: url + sorted(key+value for each form param).
     */
    private static WebhookRequest signedSmsRequest(final String from, final String to,
                                                   final String body) {
        // Build sorted params map
        final TreeMap<String, String> params = new TreeMap<>();
        params.put("From", from);
        params.put("To", to);
        params.put("Body", body);
        params.put("MessageSid", "SM123456");

        // Form-encode the body
        final StringBuilder formBody = new StringBuilder();
        params.forEach((k, v) -> {
            if (!formBody.isEmpty()) formBody.append("&");
            formBody.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                    .append("=")
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });

        // Compute Twilio signature: HMAC-SHA1(auth_token, url + sorted_params)
        // sorted_params = each key+value concatenated (no separator)
        final StringBuilder toSign = new StringBuilder(TEST_URL);
        params.forEach((k, v) -> toSign.append(k).append(v));

        final byte[] hmac = SigHelper.hmac("HmacSHA1",
                AUTH_TOKEN.getBytes(StandardCharsets.UTF_8),
                toSign.toString().getBytes(StandardCharsets.UTF_8));
        final String sig = Base64.getEncoder().encodeToString(hmac);

        return new WebhookRequest(
                formBody.toString(),
                Map.of("x-twilio-signature", List.of(sig)),
                Map.of(),
                HttpMethod.POST,
                TEST_URL);
    }

    // ── Blank token ───────────────────────────────────────────────────────────

    @Test
    void blankAuthToken_returnsIgnored() {
        connector.authToken = "";

        assertThat(connector.handle(signedSmsRequest("+447700900001", "+447700900002", "hi")))
                .isInstanceOf(WebhookResult.Ignored.class);
    }

    // ── Signature verification ────────────────────────────────────────────────

    @Test
    void validSignature_returnsDelivered() {
        final WebhookResult result = connector.handle(
                signedSmsRequest("+447700900001", "+447700900002", "hello"));

        assertThat(result).isInstanceOf(WebhookResult.Delivered.class);
    }

    @Test
    void invalidSignature_returnsUnauthorized() {
        final WebhookRequest req = new WebhookRequest(
                "From=%2B447700900001&To=%2B447700900002&Body=hi",
                Map.of("x-twilio-signature", List.of("invalidsig==")),
                Map.of(),
                HttpMethod.POST,
                TEST_URL);

        assertThat(connector.handle(req)).isInstanceOf(WebhookResult.Unauthorized.class);
    }

    @Test
    void missingSigHeader_returnsUnauthorized() {
        final WebhookRequest req = new WebhookRequest(
                "From=%2B1&To=%2B2&Body=hi", Map.of(), Map.of(), HttpMethod.POST, TEST_URL);

        assertThat(connector.handle(req)).isInstanceOf(WebhookResult.Unauthorized.class);
    }

    // ── Field mapping ─────────────────────────────────────────────────────────

    @Test
    void validSms_fieldsCorrectlyMapped() {
        final WebhookResult result = connector.handle(
                signedSmsRequest("+447700900001", "+447700900002", "test message"));

        final WebhookResult.Delivered delivered = (WebhookResult.Delivered) result;
        assertThat(delivered.messages()).hasSize(1);
        assertThat(delivered.messages().get(0).externalSenderId()).isEqualTo("+447700900001");
        assertThat(delivered.messages().get(0).externalChannelRef()).isEqualTo("+447700900002");
        assertThat(delivered.messages().get(0).content()).isEqualTo("test message");
        assertThat(delivered.messages().get(0).connectorId()).isEqualTo("twilio-sms-inbound");
        assertThat(delivered.messages().get(0).metadata()).containsEntry("message-sid", "SM123456");
    }

    @Test
    void id_isTwilioSmsInbound() {
        assertThat(connector.id()).isEqualTo("twilio-sms-inbound");
    }
}
