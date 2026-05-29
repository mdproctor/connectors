package io.casehub.connectors.email.inbound;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;

import io.casehub.connectors.InboundMessage;

@ApplicationScoped
public class InboundMessageCapture {

    private final BlockingQueue<InboundMessage> queue = new LinkedBlockingQueue<>();

    public void observe(@ObservesAsync InboundMessage message) {
        queue.offer(message);
    }

    public InboundMessage poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    public void clear() {
        queue.clear();
    }
}
