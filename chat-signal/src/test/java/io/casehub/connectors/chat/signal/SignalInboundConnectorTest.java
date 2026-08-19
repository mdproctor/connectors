package io.casehub.connectors.chat.signal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.casehub.connectors.InboundConnectorIds;
import io.casehub.connectors.InboundConnectorTypes;
import io.casehub.connectors.InboundMessage;
import io.casehub.connectors.signal.cli.SignalClient;
import io.casehub.connectors.signal.cli.model.SignalMessage;

class SignalInboundConnectorTest {

    @Test
    void id_returns_signal_inbound() {
        SignalInboundConnector connector = new SignalInboundConnector(
                new SignalClient("http://localhost:1"), "", "");
        assertThat(connector.id()).isEqualTo("signal-inbound");
    }

    @Test
    void handleMessage_delivers_direct_message() {
        SignalClient client = new SignalClient("http://localhost:1");
        SignalInboundConnector connector = new SignalInboundConnector(
                client, "http://localhost:8080", "+15551000000");

        List<InboundMessage> received = new ArrayList<>();
        SignalMessage msg = new SignalMessage(
                "+15552000000", 1724025600000L, null, "Hello",
                List.of(), null, null);

        connector.handleMessage(msg, received::add);

        assertThat(received).hasSize(1);
        InboundMessage inbound = received.get(0);
        assertThat(inbound.connectorId()).isEqualTo(InboundConnectorIds.SIGNAL_INBOUND);
        assertThat(inbound.connectorType()).isEqualTo(InboundConnectorTypes.SIGNAL);
        assertThat(inbound.externalSenderId()).isEqualTo("+15552000000");
        assertThat(inbound.externalChannelRef()).isEqualTo("+15552000000");
        assertThat(inbound.content()).isEqualTo("Hello");
        assertThat(inbound.metadata().get("signal-sender")).isEqualTo("+15552000000");
        assertThat(inbound.metadata().get("signal-timestamp")).isEqualTo("1724025600000");
    }

    @Test
    void handleMessage_delivers_group_message() {
        SignalClient client = new SignalClient("http://localhost:1");
        SignalInboundConnector connector = new SignalInboundConnector(
                client, "http://localhost:8080", "+15551000000");

        List<InboundMessage> received = new ArrayList<>();
        SignalMessage msg = new SignalMessage(
                "+15552000000", 1724025600000L, "Z3JvdXAx", "Group msg",
                List.of(), null, null);

        connector.handleMessage(msg, received::add);

        assertThat(received).hasSize(1);
        assertThat(received.get(0).externalChannelRef()).isEqualTo("Z3JvdXAx");
    }

    @Test
    void handleMessage_includes_quote_metadata() {
        SignalClient client = new SignalClient("http://localhost:1");
        SignalInboundConnector connector = new SignalInboundConnector(
                client, "http://localhost:8080", "+15551000000");

        List<InboundMessage> received = new ArrayList<>();
        SignalMessage msg = new SignalMessage(
                "+15552000000", 1724025600060L, "Z3JvdXAx", "Reply",
                List.of(), "+15553000000", 1724025600000L);

        connector.handleMessage(msg, received::add);

        assertThat(received).hasSize(1);
        assertThat(received.get(0).metadata().get("signal-quote-sender")).isEqualTo("+15553000000");
        assertThat(received.get(0).metadata().get("signal-quote-timestamp")).isEqualTo("1724025600000");
    }

    @Test
    void start_does_nothing_when_unconfigured() {
        SignalInboundConnector connector = new SignalInboundConnector(
                new SignalClient("http://localhost:1"), "", "");

        List<InboundMessage> received = new ArrayList<>();
        connector.start(received::add);

        assertThat(received).isEmpty();
    }
}
