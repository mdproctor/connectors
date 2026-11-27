package io.casehub.connectors.voice.sherpa;

import io.casehub.connectors.voice.spi.EndpointAction;
import io.casehub.connectors.voice.spi.EndpointDetectionPolicy;
import io.casehub.connectors.voice.spi.RecognitionStream;

public class SherpaEndpointDetectionPolicy implements EndpointDetectionPolicy {

    @Override
    public EndpointAction evaluate(RecognitionStream stream, String currentPartial) {
        if (currentPartial == null || currentPartial.isEmpty()) return EndpointAction.CONTINUE;
        return stream.isEndpointDetected() ? EndpointAction.ENDPOINT_DETECTED : EndpointAction.CONTINUE;
    }
}
