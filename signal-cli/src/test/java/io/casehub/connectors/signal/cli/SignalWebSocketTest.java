package io.casehub.connectors.signal.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.casehub.connectors.signal.cli.model.SignalMessage;

class SignalWebSocketTest {

    @Test
    void parses_direct_message_event() {
        String json = """
                {"envelope":{"source":"+15552000000","timestamp":1724025600000,\
                "dataMessage":{"message":"Hello","attachments":[]}}}""";

        SignalMessage msg = SignalWebSocket.parseEvent(json);

        assertThat(msg).isNotNull();
        assertThat(msg.sender()).isEqualTo("+15552000000");
        assertThat(msg.timestamp()).isEqualTo(1724025600000L);
        assertThat(msg.message()).isEqualTo("Hello");
        assertThat(msg.groupId()).isNull();
        assertThat(msg.channelRef()).isEqualTo("+15552000000");
    }

    @Test
    void parses_group_message_event() {
        String json = """
                {"envelope":{"source":"+15552000000","timestamp":1724025600001,\
                "dataMessage":{"message":"Group msg","groupInfo":{"groupId":"Z3JvdXAx"},\
                "attachments":[]}}}""";

        SignalMessage msg = SignalWebSocket.parseEvent(json);

        assertThat(msg).isNotNull();
        assertThat(msg.groupId()).isEqualTo("Z3JvdXAx");
        assertThat(msg.channelRef()).isEqualTo("Z3JvdXAx");
    }

    @Test
    void parses_quote_reply() {
        String json = """
                {"envelope":{"source":"+15552000000","timestamp":1724025600002,\
                "dataMessage":{"message":"Reply","attachments":[],\
                "quote":{"id":1724025600000,"author":"+15553000000"}}}}""";

        SignalMessage msg = SignalWebSocket.parseEvent(json);

        assertThat(msg).isNotNull();
        assertThat(msg.quoteSender()).isEqualTo("+15553000000");
        assertThat(msg.quoteTimestamp()).isEqualTo(1724025600000L);
    }

    @Test
    void parses_attachments() {
        String json = """
                {"envelope":{"source":"+15552000000","timestamp":123,\
                "dataMessage":{"message":"Photo","attachments":[{"id":"att1"},{"id":"att2"}]}}}""";

        SignalMessage msg = SignalWebSocket.parseEvent(json);

        assertThat(msg).isNotNull();
        assertThat(msg.attachmentIds()).containsExactly("att1", "att2");
    }

    @Test
    void returns_null_for_typing_indicator() {
        String json = """
                {"envelope":{"source":"+15552000000","timestamp":123,\
                "typingMessage":{"action":"STARTED"}}}""";

        assertThat(SignalWebSocket.parseEvent(json)).isNull();
    }

    @Test
    void returns_null_for_receipt() {
        String json = """
                {"envelope":{"source":"+15552000000","timestamp":123,\
                "receiptMessage":{"type":"READ"}}}""";

        assertThat(SignalWebSocket.parseEvent(json)).isNull();
    }

    @Test
    void returns_null_for_invalid_json() {
        assertThat(SignalWebSocket.parseEvent("not json")).isNull();
    }

    @Test
    void returns_null_for_empty_envelope() {
        assertThat(SignalWebSocket.parseEvent("{}")).isNull();
    }
}
