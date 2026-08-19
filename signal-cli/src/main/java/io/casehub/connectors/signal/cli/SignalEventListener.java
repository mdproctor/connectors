package io.casehub.connectors.signal.cli;

import io.casehub.connectors.signal.cli.model.SignalMessage;

public interface SignalEventListener {
    void onMessage(SignalMessage message);
}
