package io.casehub.connectors.signal.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.connectors.signal.cli.model.SendResponse;
import io.casehub.connectors.signal.cli.model.SignalContact;
import io.casehub.connectors.signal.cli.model.SignalGroup;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    public List<SignalGroup> listGroups(final String number) {
        try {
            final HttpResponse<String> resp = get("/v1/groups/" + number);
            if (!isSuccess(resp)) {return List.of();}
            final JsonNode          array  = MAPPER.readTree(resp.body());
            final List<SignalGroup> groups = new ArrayList<>();
            for (final JsonNode node : array) {
                final List<String> members = new ArrayList<>();
                if (node.has("members")) {
                    for (final JsonNode m : node.get("members")) {
                        members.add(m.asText());
                    }
                }
                groups.add(new SignalGroup(
                        node.get("id").asText(),
                        node.has("name") ? node.get("name").asText() : null,
                        node.has("description") ? node.get("description").asText() : null,
                        List.copyOf(members)));
            }
            return groups;
        } catch (final Exception e) {
            LOG.log(Level.WARNING, "signal-cli listGroups error", e);
            return List.of();
        }
    }

    public SignalGroup getGroup(final String number, final String groupId) {
        try {
            final HttpResponse<String> resp = get("/v1/groups/" + number + "/" + groupId);
            if (!isSuccess(resp)) {return null;}
            final JsonNode     node    = MAPPER.readTree(resp.body());
            final List<String> members = new ArrayList<>();
            if (node.has("members")) {
                for (final JsonNode m : node.get("members")) {
                    members.add(m.asText());
                }
            }
            return new SignalGroup(
                    node.get("id").asText(),
                    node.has("name") ? node.get("name").asText() : null,
                    node.has("description") ? node.get("description").asText() : null,
                    List.copyOf(members));
        } catch (final Exception e) {
            LOG.log(Level.WARNING, "signal-cli getGroup error", e);
            return null;
        }
    }

    public SignalGroup createGroup(final String number, final String name,
                                   final List<String> members) {
        try {
            final ObjectNode body = MAPPER.createObjectNode();
            body.put("name", name);
            final ArrayNode membersArray = body.putArray("members");
            members.forEach(membersArray::add);
            final HttpResponse<String> resp = post("/v1/groups/" + number, body);
            if (!isSuccess(resp)) {return null;}
            final JsonNode node = MAPPER.readTree(resp.body());
            return new SignalGroup(
                    node.has("id") ? node.get("id").asText() : null,
                    node.has("name") ? node.get("name").asText() : name,
                    null, members);
        } catch (final Exception e) {
            LOG.log(Level.WARNING, "signal-cli createGroup error", e);
            return null;
        }
    }

    public void deleteGroup(final String number, final String groupId) {
        try {
            delete("/v1/groups/" + number + "/" + groupId);
        } catch (final Exception e) {
            LOG.log(Level.WARNING, "signal-cli deleteGroup error", e);
        }
    }

    public void addMembers(final String number, final String groupId,
                           final List<String> members) {
        try {
            final ObjectNode body = MAPPER.createObjectNode();
            final ArrayNode  arr  = body.putArray("members");
            members.forEach(arr::add);
            post("/v1/groups/" + number + "/" + groupId + "/members", body);
        } catch (final Exception e) {
            LOG.log(Level.WARNING, "signal-cli addMembers error", e);
        }
    }

    public void removeMembers(final String number, final String groupId,
                              final List<String> members) {
        try {
            final ObjectNode body = MAPPER.createObjectNode();
            final ArrayNode  arr  = body.putArray("members");
            members.forEach(arr::add);
            deleteWithBody("/v1/groups/" + number + "/" + groupId + "/members", body);
        } catch (final Exception e) {
            LOG.log(Level.WARNING, "signal-cli removeMembers error", e);
        }
    }

    public void addReaction(final String number, final String recipient,
                            final String emoji, final String targetAuthor,
                            final long targetTimestamp) {
        try {
            final ObjectNode body = MAPPER.createObjectNode();
            body.put("recipient", recipient);
            body.put("reaction", emoji);
            body.put("target_author", targetAuthor);
            body.put("timestamp", targetTimestamp);
            post("/v1/reactions/" + number, body);
        } catch (final Exception e) {
            LOG.log(Level.WARNING, "signal-cli addReaction error", e);
        }
    }

    public void removeReaction(final String number, final String recipient,
                               final String emoji, final String targetAuthor,
                               final long targetTimestamp) {
        try {
            final ObjectNode body = MAPPER.createObjectNode();
            body.put("recipient", recipient);
            body.put("reaction", emoji);
            body.put("target_author", targetAuthor);
            body.put("timestamp", targetTimestamp);
            deleteWithBody("/v1/reactions/" + number, body);
        } catch (final Exception e) {
            LOG.log(Level.WARNING, "signal-cli removeReaction error", e);
        }
    }

    public List<SignalContact> listContacts(final String number) {
        try {
            final HttpResponse<String> resp = get("/v1/contacts/" + number);
            if (!isSuccess(resp)) {return List.of();}
            final JsonNode            array    = MAPPER.readTree(resp.body());
            final List<SignalContact> contacts = new ArrayList<>();
            for (final JsonNode node : array) {
                contacts.add(new SignalContact(
                        node.has("number") ? node.get("number").asText() : null,
                        node.has("profileName") ? node.get("profileName").asText() : null));
            }
            return contacts;
        } catch (final Exception e) {
            LOG.log(Level.WARNING, "signal-cli listContacts error", e);
            return List.of();
        }
    }

    public byte[] downloadAttachment(final String attachmentId) {
        try {
            final HttpResponse<byte[]> resp = http.send(
                    HttpRequest.newBuilder()
                               .uri(URI.create(apiUrl + "/v1/attachments/" + attachmentId))
                               .GET()
                               .timeout(TIMEOUT)
                               .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (isSuccess(resp)) {return resp.body();}
            return null;
        } catch (final Exception e) {
            LOG.log(Level.WARNING, "signal-cli downloadAttachment error", e);
            return null;
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

    private HttpResponse<String> get(final String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                           .uri(URI.create(apiUrl + path))
                           .GET()
                           .timeout(TIMEOUT)
                           .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void delete(final String path) throws Exception {
        http.send(
                HttpRequest.newBuilder()
                           .uri(URI.create(apiUrl + path))
                           .DELETE()
                           .timeout(TIMEOUT)
                           .build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private void deleteWithBody(final String path, final ObjectNode body) throws Exception {
        http.send(
                HttpRequest.newBuilder()
                           .uri(URI.create(apiUrl + path))
                           .method("DELETE", HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                           .header("Content-Type", "application/json")
                           .timeout(TIMEOUT)
                           .build(),
                HttpResponse.BodyHandlers.discarding());
    }


    private static boolean isSuccess(final HttpResponse<?> resp) {
        return resp.statusCode() >= 200 && resp.statusCode() < 300;
    }
}
