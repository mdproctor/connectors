package io.casehub.connectors;

/**
 * Callback delivered to {@link InboundConnector#start(InboundMessageSink)} at startup.
 * Pull-based connectors call {@code receive()} when a new message arrives.
 */
@FunctionalInterface
public interface InboundMessageSink {
    void receive(InboundMessage message);
}
