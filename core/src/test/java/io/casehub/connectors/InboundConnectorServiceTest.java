package io.casehub.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class InboundConnectorServiceTest {

    // ── Test fixtures ───────────────────────────────────────────────────────

    private static class RecordingConnector implements InboundConnector {
        final String connectorId;
        final List<InboundMessage> received = new ArrayList<>();
        int startCount = 0;
        int stopCount = 0;

        RecordingConnector(final String id) {
            this.connectorId = id;
        }

        @Override public String id() { return connectorId; }

        @Override
        public void start(final InboundMessageSink sink) {
            startCount++;
            // simulate a message arriving after start
        }

        @Override
        public void stop() {
            stopCount++;
        }

        void simulateMessage(final InboundMessageSink sink, final InboundMessage msg) {
            sink.receive(msg);
        }
    }

    private static InboundMessage sampleMessage(final String connectorId) {
        return new InboundMessage(connectorId, "sender-1", "channel-1", "hello", Instant.now());
    }

    // ── Registry construction ───────────────────────────────────────────────

    @Test
    void constructor_registersAllConnectors() {
        final RecordingConnector a = new RecordingConnector("email-inbound");
        final RecordingConnector b = new RecordingConnector("jira-inbound");
        final List<InboundMessage> captured = new ArrayList<>();

        final InboundConnectorService service = new InboundConnectorService(
                List.of(a, b), captured::add);

        assertThat(service.pullIds()).containsExactlyInAnyOrder("email-inbound", "jira-inbound");
    }

    @Test
    void constructor_duplicateId_throwsIllegalStateException() {
        final RecordingConnector a = new RecordingConnector("email-inbound");
        final RecordingConnector b = new RecordingConnector("email-inbound");

        assertThatThrownBy(() -> new InboundConnectorService(List.of(a, b), msg -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("email-inbound");
    }

    @Test
    void constructor_invalidId_throwsIllegalStateException() {
        final RecordingConnector bad = new RecordingConnector("UPPER_CASE");

        assertThatThrownBy(() -> new InboundConnectorService(List.of(bad), msg -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UPPER_CASE");
    }

    @Test
    void constructor_idWithSlash_throwsIllegalStateException() {
        final RecordingConnector bad = new RecordingConnector("slack/inbound");

        assertThatThrownBy(() -> new InboundConnectorService(List.of(bad), msg -> {}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructor_emptyList_succeeds() {
        final InboundConnectorService service = new InboundConnectorService(List.of(), msg -> {});
        assertThat(service.pullIds()).isEmpty();
    }

    // ── ID validation — valid patterns ──────────────────────────────────────

    @Test
    void constructor_validIds_accepted() {
        final InboundConnectorService service = new InboundConnectorService(
                List.of(
                        new RecordingConnector("email-inbound"),
                        new RecordingConnector("a"),
                        new RecordingConnector("abc123"),
                        new RecordingConnector("a-b-c")),
                msg -> {});
        assertThat(service.pullIds()).hasSize(4);
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    @Test
    void onStart_callsStartOnAllConnectors() {
        final RecordingConnector a = new RecordingConnector("email-inbound");
        final RecordingConnector b = new RecordingConnector("jira-inbound");
        final InboundConnectorService service = new InboundConnectorService(
                List.of(a, b), msg -> {});

        service.onStart(null);

        assertThat(a.startCount).isEqualTo(1);
        assertThat(b.startCount).isEqualTo(1);
    }

    @Test
    void onStop_callsStopOnAllConnectors() {
        final RecordingConnector a = new RecordingConnector("email-inbound");
        final RecordingConnector b = new RecordingConnector("jira-inbound");
        final InboundConnectorService service = new InboundConnectorService(
                List.of(a, b), msg -> {});

        service.onStop(null);

        assertThat(a.stopCount).isEqualTo(1);
        assertThat(b.stopCount).isEqualTo(1);
    }

    @Test
    void onStart_sinkPassedToConnector_deliversMessages() {
        final List<InboundMessage> captured = new ArrayList<>();
        final InboundMessage msg = sampleMessage("email-inbound");

        // Connector that immediately delivers a message when started
        final InboundConnector eager = new InboundConnector() {
            @Override public String id() { return "email-inbound"; }

            @Override
            public void start(final InboundMessageSink sink) {
                sink.receive(msg);
            }

            @Override public void stop() {}
        };

        final InboundConnectorService service = new InboundConnectorService(
                List.of(eager), captured::add);
        service.onStart(null);

        assertThat(captured).containsExactly(msg);
    }

    // ── receive() ───────────────────────────────────────────────────────────

    @Test
    void receive_forwardsToEventBus() {
        final List<InboundMessage> captured = new ArrayList<>();
        final InboundConnectorService service = new InboundConnectorService(
                List.of(), captured::add);
        final InboundMessage msg = sampleMessage("slack-inbound");

        service.receive(msg);

        assertThat(captured).containsExactly(msg);
    }

    @Test
    void receive_multipleMessages_allForwarded() {
        final List<InboundMessage> captured = new ArrayList<>();
        final InboundConnectorService service = new InboundConnectorService(
                List.of(), captured::add);

        final InboundMessage m1 = sampleMessage("slack-inbound");
        final InboundMessage m2 = sampleMessage("teams-inbound");
        service.receive(m1);
        service.receive(m2);

        assertThat(captured).containsExactly(m1, m2);
    }

    // ── pullIds() ────────────────────────────────────────────────────────────

    @Test
    void pullIds_returnsImmutableView() {
        final InboundConnectorService service = new InboundConnectorService(
                List.of(new RecordingConnector("email-inbound")), msg -> {});

        assertThatThrownBy(() -> service.pullIds().add("new"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
