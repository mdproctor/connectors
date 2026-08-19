package io.casehub.connectors.signal.cli;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.casehub.connectors.signal.cli.model.SendResponse;

public class SignalClient {

    private static final Logger LOG = Logger.getLogger(SignalClient.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String apiUrl;
    private final HttpClient http;

    public SignalClient(final String apiUrl) {
        this.apiUrl = apiUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public SendResponse send(final String number, final String recipient,
                             final String message, final List<String> base64Attachments) {
        try {
            final ObjectNode body = MAPPER.createObjectNode();
            body.put("number", number);
            body.put("message", message);

            if (recipient.startsWith("+")) {
                final ArrayNode recipients = body.putArray("recipients");
                recipients.add(recipient);
            } else {
                body.put("base64_group_id", recipient);
            }

            if (base64Attachments != null && !base64Attachments.isEmpty()) {
                final ArrayNode atts = body.putArray("base64_attachments");
                base64Attachments.forEach(atts::add);
            }

            final HttpResponse<String> resp = post("/v2/send", body);

            if (isSuccess(resp)) {
                final JsonNode json = MAPPER.readTree(resp.body());
                final String ts = json.has("timestamp") ? json.get("timestamp").asText() : null;
                return SendResponse.success(ts);
            }
            LOG.warning("signal-cli send failed: HTTP " + resp.statusCode() + " — " + resp.body());
            return SendResponse.failure();
        } catch (final Exception e) {
            LOG.log(Level.WARNING, "signal-cli send error", e);
            return SendResponse.failure();
        }
    }

    public boolean health() {
        try {
            final HttpResponse<Void> resp = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(apiUrl + "/v1/health"))
                            .GET()
                            .timeout(Duration.ofSeconds(5))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (final Exception e) {
            return false;
        }
    }

    private HttpResponse<String> post(final String path, final ObjectNode body) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                        .timeout(TIMEOUT)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static boolean isSuccess(final HttpResponse<?> resp) {
        return resp.statusCode() >= 200 && resp.statusCode() < 300;
    }
}
