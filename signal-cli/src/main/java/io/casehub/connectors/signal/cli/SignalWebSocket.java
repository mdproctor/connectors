package io.casehub.connectors.signal.cli;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.connectors.signal.cli.model.SignalMessage;

public class SignalWebSocket {

    private static final Logger LOG = Logger.getLogger(SignalWebSocket.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MAX_BACKOFF_MS = 30_000;

    private final String wsUrl;
    private final SignalEventListener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile WebSocket webSocket;

    public SignalWebSocket(final String apiUrl, final SignalEventListener listener) {
        this.wsUrl = apiUrl.replaceFirst("^http", "ws") + "/v1/receive";
        this.listener = listener;
    }

    public void connect() {
        if (running.getAndSet(true)) return;
        Thread.ofVirtual().name("signal-ws-connect").start(this::connectLoop);
    }

    public void disconnect() {
        running.set(false);
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
            } catch (final Exception ignored) {}
            webSocket = null;
        }
    }

    public boolean isConnected() {
        return running.get() && webSocket != null;
    }

    private void connectLoop() {
        long backoff = 1000;
        while (running.get()) {
            try {
                final HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();

                webSocket = client.newWebSocketBuilder()
                        .buildAsync(URI.create(wsUrl), new Listener())
                        .join();

                LOG.info("signal-ws: connected to " + wsUrl);
                backoff = 1000;

                while (running.get() && webSocket != null) {
                    Thread.sleep(1000);
                }
            } catch (final Exception e) {
                if (!running.get()) break;
                LOG.log(Level.WARNING, "signal-ws: connection failed, retrying in " + backoff + "ms", e);
                try {
                    long jitter = ThreadLocalRandom.current().nextLong(backoff / 4);
                    Thread.sleep(backoff + jitter);
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
    }

    static SignalMessage parseEvent(final String json) {
        try {
            final JsonNode root = MAPPER.readTree(json);
            final JsonNode envelope = root.get("envelope");
            if (envelope == null) return null;

            final JsonNode dataMessage = envelope.get("dataMessage");
            if (dataMessage == null) return null;

            final String sender = envelope.has("source")
                    ? envelope.get("source").asText() : null;
            final long timestamp = envelope.has("timestamp")
                    ? envelope.get("timestamp").asLong() : 0;
            final String message = dataMessage.has("message")
                    ? dataMessage.get("message").asText() : "";

            String groupId = null;
            if (dataMessage.has("groupInfo") && dataMessage.get("groupInfo").has("groupId")) {
                groupId = dataMessage.get("groupInfo").get("groupId").asText();
            }

            final List<String> attachmentIds = new ArrayList<>();
            if (dataMessage.has("attachments") && dataMessage.get("attachments").isArray()) {
                for (final JsonNode att : dataMessage.get("attachments")) {
                    if (att.has("id")) {
                        attachmentIds.add(att.get("id").asText());
                    }
                }
            }

            String quoteSender = null;
            Long quoteTimestamp = null;
            if (dataMessage.has("quote")) {
                final JsonNode quote = dataMessage.get("quote");
                if (quote.has("author")) quoteSender = quote.get("author").asText();
                if (quote.has("id")) quoteTimestamp = quote.get("id").asLong();
            }

            return new SignalMessage(sender, timestamp, groupId, message,
                    List.copyOf(attachmentIds), quoteSender, quoteTimestamp);
        } catch (final Exception e) {
            LOG.log(Level.WARNING, "signal-ws: failed to parse event", e);
            return null;
        }
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(final WebSocket ws, final CharSequence data,
                                          final boolean last) {
            buffer.append(data);
            if (last) {
                final String text = buffer.toString();
                buffer.setLength(0);
                final SignalMessage msg = parseEvent(text);
                if (msg != null) {
                    try {
                        listener.onMessage(msg);
                    } catch (final Exception e) {
                        LOG.log(Level.WARNING, "signal-ws: listener threw", e);
                    }
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(final WebSocket ws, final int statusCode,
                                           final String reason) {
            LOG.info("signal-ws: closed (" + statusCode + ": " + reason + ")");
            webSocket = null;
            return null;
        }

        @Override
        public void onError(final WebSocket ws, final Throwable error) {
            LOG.log(Level.WARNING, "signal-ws: error", error);
            webSocket = null;
        }
    }
}
