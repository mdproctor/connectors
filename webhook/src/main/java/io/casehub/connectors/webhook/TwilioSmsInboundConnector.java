package io.casehub.connectors.webhook;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.casehub.connectors.HttpMethod;
import io.casehub.connectors.InboundMessage;
import io.casehub.connectors.WebhookInboundConnector;
import io.casehub.connectors.WebhookRequest;
import io.casehub.connectors.WebhookResult;

/**
 * Inbound connector for Twilio SMS webhooks.
 *
 * <p>Twilio sends form-encoded POST requests when an SMS is received.
 *
 * <h2>Signature (SHA-1 — Twilio's specified algorithm)</h2>
 * HMAC-SHA1 of {@code requestUrl + sorted_form_params}, where sorted form params are
 * each key+value pair concatenated in alphabetical order (no separator between pairs).
 * The result is Base64-encoded and compared to {@code X-Twilio-Signature}
 * using constant-time comparison.
 *
 * <p>SHA-1 is Twilio's specified algorithm per their
 * <a href="https://www.twilio.com/docs/usage/security">webhook security docs</a>;
 * it is not configurable.
 *
 * <h2>Reverse proxy</h2>
 * {@code requestUrl} must match the public URL Twilio used when signing. If the service
 * runs behind a load balancer, configure:
 * <pre>
 * quarkus.http.proxy.proxy-address-forwarding=true
 * quarkus.http.proxy.allow-forwarded=true
 * </pre>
 */
@ApplicationScoped
public class TwilioSmsInboundConnector extends WebhookInboundConnector {

    static final String ID = "twilio-sms-inbound";

    private static final Logger LOG = Logger.getLogger(TwilioSmsInboundConnector.class.getName());

    @ConfigProperty(name = "casehub.connectors.twilio-sms-inbound.auth-token", defaultValue = "")
    String authToken;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public WebhookResult handle(final WebhookRequest request) {
        try {
            return doHandle(request);
        } catch (final Exception e) {
            LOG.severe("Unexpected error in TwilioSmsInboundConnector: " + e.getMessage());
            return new WebhookResult.Ignored();
        }
    }

    private WebhookResult doHandle(final WebhookRequest request) {
        if (authToken.isBlank()) {
            LOG.warning("twilio-sms-inbound: auth-token not configured — ignoring request");
            return new WebhookResult.Ignored();
        }

        final Map<String, String> params = parseFormBody(request.body());

        if (!verifySignature(request.requestUrl(), params, request.header("x-twilio-signature"))) {
            return new WebhookResult.Unauthorized();
        }

        final String from = params.get("From");
        final String to = params.get("To");
        final String body = params.getOrDefault("Body", "");

        if (from == null || to == null) {
            LOG.warning("twilio-sms-inbound: missing From or To field");
            return new WebhookResult.Ignored();
        }

        final String messageSid = params.get("MessageSid");
        final Map<String, String> meta = messageSid != null
                ? Map.of("message-sid", messageSid) : Map.of();
        return new WebhookResult.Delivered(
                List.of(new InboundMessage(ID, from, to, body, Instant.now(), meta)));
    }

    private boolean verifySignature(final String url, final Map<String, String> params,
                                    final String sigHeader) {
        if (sigHeader == null || sigHeader.isBlank()) return false;
        // Twilio: sign url + sorted(key+value) for each form param
        final StringBuilder toSign = new StringBuilder(url);
        new TreeMap<>(params).forEach((k, v) -> toSign.append(k).append(v));

        final byte[] hmac = SigHelper.hmac("HmacSHA1",
                authToken.getBytes(StandardCharsets.UTF_8),
                toSign.toString().getBytes(StandardCharsets.UTF_8));
        final byte[] expected = Base64.getEncoder().encodeToString(hmac)
                .getBytes(StandardCharsets.UTF_8);
        return SigHelper.constantTimeEquals(expected, sigHeader.trim()
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Parse {@code application/x-www-form-urlencoded} body into a map. */
    static Map<String, String> parseFormBody(final String body) {
        final Map<String, String> result = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return result;
        for (final String pair : body.split("&")) {
            final int eq = pair.indexOf('=');
            if (eq < 0) continue;
            final String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            final String val = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.put(key, val);
        }
        return result;
    }
}
