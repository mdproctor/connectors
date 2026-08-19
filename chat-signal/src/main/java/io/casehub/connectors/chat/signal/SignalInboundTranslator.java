package io.casehub.connectors.chat.signal;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.connectors.InboundConnectorTypes;
import io.casehub.connectors.InboundMessage;
import io.casehub.connectors.chat.model.ChatChannelRef;
import io.casehub.connectors.chat.model.ChatContent;
import io.casehub.connectors.chat.model.ChatMessageRef;
import io.casehub.connectors.chat.model.MemberRef;
import io.casehub.connectors.chat.model.ReceivedMessage;
import io.casehub.connectors.chat.spi.InboundTranslator;

@ApplicationScoped
public class SignalInboundTranslator implements InboundTranslator {

    @Override
    public String connectorType() {
        return InboundConnectorTypes.SIGNAL;
    }

    @Override
    public ReceivedMessage translate(final InboundMessage msg) {
        final var channel = new ChatChannelRef(msg.externalChannelRef());
        final var messageRef = new ChatMessageRef(channel,
                msg.metadata().get("signal-sender") + ":"
                + msg.metadata().get("signal-timestamp"));

        final String quoteSender = msg.metadata().get("signal-quote-sender");
        final String quoteTs = msg.metadata().get("signal-quote-timestamp");
        final ChatMessageRef parentRef = quoteSender != null && quoteTs != null
                ? new ChatMessageRef(channel, quoteSender + ":" + quoteTs)
                : null;

        return new ReceivedMessage(
                InboundConnectorTypes.SIGNAL,
                channel,
                messageRef,
                parentRef,
                new MemberRef(msg.externalSenderId()),
                new ChatContent(msg.content(), null, msg.attachments(), List.of()),
                msg.receivedAt());
    }
}
