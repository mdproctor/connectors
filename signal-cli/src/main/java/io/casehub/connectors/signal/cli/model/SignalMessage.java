package io.casehub.connectors.signal.cli.model;

import java.util.List;

public record SignalMessage(
        String sender,
        long timestamp,
        String groupId,
        String message,
        List<String> attachmentIds,
        String quoteSender,
        Long quoteTimestamp) {

    public String channelRef() {
        return groupId != null ? groupId : sender;
    }
}
