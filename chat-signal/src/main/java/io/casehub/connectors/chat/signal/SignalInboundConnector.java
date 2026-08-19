package io.casehub.connectors.chat.signal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.casehub.connectors.Attachment;
import io.casehub.connectors.InboundConnector;
import io.casehub.connectors.InboundConnectorIds;
import io.casehub.connectors.InboundConnectorTypes;
import io.casehub.connectors.InboundMessage;
import io.casehub.connectors.InboundMessageSink;
import io.casehub.connectors.signal.cli.SignalClient;
import io.casehub.connectors.signal.cli.SignalWebSocket;
import io.casehub.connectors.signal.cli.model.SignalMessage;

@ApplicationScoped
public class SignalInboundConnector implements InboundConnector {

    private static final Logger LOG = Logger.getLogger(SignalInboundConnector.class.getName());

    private final SignalClient client;
    private final String apiUrl;
    private final String number;

    private volatile SignalWebSocket webSocket;
    private volatile boolean stopping = false;

    @Inject
    public SignalInboundConnector(
            final SignalClient client,
            @ConfigProperty(name = "casehub.signal.api-url", defaultValue = "") final String apiUrl,
            @ConfigProperty(name = "casehub.signal.number", defaultValue = "") final String number) {
        this.client = client;
        this.apiUrl = apiUrl;
        this.number = number;
    }

    @Override
    public String id() {
        return InboundConnectorIds.SIGNAL_INBOUND;
    }

    @Override
    public void start(final InboundMessageSink sink) {
        if (apiUrl.isBlank() || number.isBlank()) {
            LOG.warning("signal-inbound: not configured, connector inactive");
            return;
        }

        webSocket = new SignalWebSocket(apiUrl, msg -> handleMessage(msg, sink));
        webSocket.connect();
        LOG.info("signal-inbound: WebSocket connection started");
    }

    @Override
    public void stop() {
        stopping = true;
        if (webSocket != null) {
            webSocket.disconnect();
            webSocket = null;
        }
    }

    void handleMessage(final SignalMessage msg, final InboundMessageSink sink) {
        if (stopping) return;

        try {
            final List<Attachment> attachments;
            int downloadFailures = 0;

            if (msg.attachmentIds().isEmpty()) {
                attachments = List.of();
            } else {
                attachments = new ArrayList<>();
                for (final String attId : msg.attachmentIds()) {
                    final byte[] data = client.downloadAttachment(attId);
                    if (data != null) {
                        attachments.add(new Attachment(attId, "application/octet-stream", data));
                    } else {
                        downloadFailures++;
                    }
                }
            }

            final Map<String, String> metadata = new HashMap<>();
            metadata.put("signal-sender", msg.sender());
            metadata.put("signal-timestamp", String.valueOf(msg.timestamp()));

            if (msg.quoteSender() != null && msg.quoteTimestamp() != null) {
                metadata.put("signal-quote-sender", msg.quoteSender());
                metadata.put("signal-quote-timestamp", String.valueOf(msg.quoteTimestamp()));
            }

            if (!msg.attachmentIds().isEmpty()) {
                metadata.put("signal-attachment-count", String.valueOf(msg.attachmentIds().size()));
                metadata.put("signal-attachment-download-failures", String.valueOf(downloadFailures));
            }

            final InboundMessage inbound = new InboundMessage(
                    InboundConnectorIds.SIGNAL_INBOUND,
                    InboundConnectorTypes.SIGNAL,
                    msg.sender(),
                    msg.channelRef(),
                    msg.message(),
                    attachments,
                    Instant.now(),
                    metadata,
                    null);

            sink.receive(inbound);
        } catch (final Exception e) {
            LOG.log(Level.SEVERE, "signal-inbound: error handling message", e);
        }
    }
}
