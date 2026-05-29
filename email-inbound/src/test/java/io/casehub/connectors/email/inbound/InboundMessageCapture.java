package io.casehub.connectors.email.inbound;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.casehub.connectors.InboundMessage;

@ApplicationScoped
public class InboundMessageCapture {

    private final List<InboundMessage> messages = Collections.synchronizedList(new ArrayList<>());

    void observe(@Observes final InboundMessage message) {
        messages.add(message);
    }

    public List<InboundMessage> messages() {
        return Collections.unmodifiableList(messages);
    }

    public void clear() {
        messages.clear();
    }
}
