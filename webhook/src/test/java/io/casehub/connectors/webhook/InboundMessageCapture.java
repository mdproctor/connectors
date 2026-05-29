package io.casehub.connectors.webhook;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.casehub.connectors.InboundMessage;

/** Test bean that captures all CDI InboundMessage events for assertion. */
@ApplicationScoped
public class InboundMessageCapture {

    private final List<InboundMessage> received = new CopyOnWriteArrayList<>();

    public void onMessage(@Observes final InboundMessage message) {
        received.add(message);
    }

    public List<InboundMessage> received() {
        return List.copyOf(received);
    }

    public void clear() {
        received.clear();
    }
}
