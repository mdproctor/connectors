package io.casehub.connectors.voice.spi;

@FunctionalInterface
public interface EndpointDetectionPolicy {
    EndpointAction evaluate(RecognitionStream stream, String currentPartial);
}
