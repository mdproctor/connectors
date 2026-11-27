package io.casehub.connectors.voice.spi;

import io.casehub.connectors.voice.model.TranscriptionResult;

public interface RecognitionStream extends AutoCloseable {
    void acceptSamples(float[] samples, int sampleRate);
    boolean isEndpointDetected();
    String partialResult();
    TranscriptionResult finalResult();

    default TranscriptionResult segmentResult() {
        return finalResult();
    }

    default void reset() {
        throw new UnsupportedOperationException("reset not supported by this implementation");
    }


    void close();
}
