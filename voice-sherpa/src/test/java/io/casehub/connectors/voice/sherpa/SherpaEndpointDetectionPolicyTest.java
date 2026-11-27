package io.casehub.connectors.voice.sherpa;

import io.casehub.connectors.voice.spi.EndpointAction;
import io.casehub.connectors.voice.spi.RecognitionStream;
import io.casehub.connectors.voice.model.TranscriptionResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SherpaEndpointDetectionPolicyTest {

    private final SherpaEndpointDetectionPolicy policy = new SherpaEndpointDetectionPolicy();

    @Test
    void continueOnNullPartial() {
        var stream = stubStream(false);
        assertThat(policy.evaluate(stream, null)).isEqualTo(EndpointAction.CONTINUE);
    }

    @Test
    void continueOnEmptyPartial() {
        var stream = stubStream(true);
        assertThat(policy.evaluate(stream, "")).isEqualTo(EndpointAction.CONTINUE);
    }

    @Test
    void continueWhenNoEndpoint() {
        var stream = stubStream(false);
        assertThat(policy.evaluate(stream, "hello")).isEqualTo(EndpointAction.CONTINUE);
    }

    @Test
    void endpointDetectedWhenStreamReportsEndpoint() {
        var stream = stubStream(true);
        assertThat(policy.evaluate(stream, "hello")).isEqualTo(EndpointAction.ENDPOINT_DETECTED);
    }

    private static RecognitionStream stubStream(boolean endpointDetected) {
        return new RecognitionStream() {
            @Override public void acceptSamples(float[] samples, int sampleRate) {}
            @Override public boolean isEndpointDetected() { return endpointDetected; }
            @Override public String partialResult() { return ""; }
            @Override public TranscriptionResult finalResult() { return new TranscriptionResult("", "", 0); }
            @Override public void close() {}
        };
    }
}
