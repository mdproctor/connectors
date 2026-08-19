package io.casehub.connectors.chat.signal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.casehub.connectors.InboundMessage;
import io.casehub.connectors.chat.model.ReceivedMessage;

class SignalInboundTranslatorTest {

    private final SignalInboundTranslator translator = new SignalInboundTranslator();

    @Test
    void connectorType_returns_signal() {
        assertThat(translator.connectorType()).isEqualTo("signal");
    }

    @Test
    void translates_direct_message() {
        InboundMessage msg = new InboundMessage(
                "signal-inbound", "signal", "+15552000000", "+15552000000",
                "Hello", List.of(), Instant.parse("2024-08-19T12:00:00Z"),
                Map.of("signal-sender", "+15552000000",
                       "signal-timestamp", "1724025600000"),
                null);

        ReceivedMessage result = translator.translate(msg);

        assertThat(result.platformId()).isEqualTo("signal");
        assertThat(result.channel().id()).isEqualTo("+15552000000");
        assertThat(result.messageRef().messageId()).isEqualTo("+15552000000:1724025600000");
        assertThat(result.parentRef()).isNull();
        assertThat(result.sender().id()).isEqualTo("+15552000000");
        assertThat(result.content().text()).isEqualTo("Hello");
    }

    @Test
    void translates_group_message() {
        InboundMessage msg = new InboundMessage(
                "signal-inbound", "signal", "+15552000000", "Z3JvdXAx",
                "Group msg", List.of(), Instant.parse("2024-08-19T12:00:00Z"),
                Map.of("signal-sender", "+15552000000",
                       "signal-timestamp", "1724025600000"),
                null);

        ReceivedMessage result = translator.translate(msg);

        assertThat(result.channel().id()).isEqualTo("Z3JvdXAx");
    }

    @Test
    void translates_quote_reply_with_parent_ref() {
        InboundMessage msg = new InboundMessage(
                "signal-inbound", "signal", "+15552000000", "Z3JvdXAx",
                "Reply", List.of(), Instant.parse("2024-08-19T12:01:00Z"),
                Map.of("signal-sender", "+15552000000",
                       "signal-timestamp", "1724025600060",
                       "signal-quote-sender", "+15553000000",
                       "signal-quote-timestamp", "1724025600000"),
                null);

        ReceivedMessage result = translator.translate(msg);

        assertThat(result.parentRef()).isNotNull();
        assertThat(result.parentRef().messageId()).isEqualTo("+15553000000:1724025600000");
    }
}
