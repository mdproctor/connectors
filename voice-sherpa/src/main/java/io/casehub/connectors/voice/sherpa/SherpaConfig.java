package io.casehub.connectors.voice.sherpa;

import io.casehub.connectors.voice.spi.CleanupConfig;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

public record SherpaConfig(Path modelDir, int numThreads, String provider,
                           @Nullable Path punctuationModelDir,
                           @Nullable CleanupConfig cleanupConfig,
                           float rule1MinTrailingSilence,
                           float rule2MinTrailingSilence) {
    public SherpaConfig {
        Objects.requireNonNull(modelDir, "modelDir");
        Objects.requireNonNull(provider, "provider");
        if (numThreads <= 0) {throw new IllegalArgumentException("numThreads must be positive: " + numThreads);}
        if (rule1MinTrailingSilence <= 0) rule1MinTrailingSilence = 2.4f;
        if (rule2MinTrailingSilence <= 0) rule2MinTrailingSilence = 1.2f;
    }

    public SherpaConfig(Path modelDir, int numThreads, String provider) {
        this(modelDir, numThreads, provider, null, null, 2.4f, 1.2f);
    }

    public SherpaConfig(Path modelDir, int numThreads, String provider,
                        @Nullable Path punctuationModelDir,
                        @Nullable CleanupConfig cleanupConfig) {
        this(modelDir, numThreads, provider, punctuationModelDir, cleanupConfig, 2.4f, 1.2f);
    }

    public static SherpaConfig defaults(Path modelDir) {
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        return new SherpaConfig(modelDir, threads, "cpu", null, null, 2.4f, 1.2f);
    }

    public static SherpaConfig defaults() {
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        return new SherpaConfig(Provisioner.defaultModelDir(), threads, "cpu", null, null, 2.4f, 1.2f);
    }

    public SherpaConfig withPunctuation(Path punctuationModelDir) {
        return new SherpaConfig(modelDir, numThreads, provider, punctuationModelDir, cleanupConfig, rule1MinTrailingSilence, rule2MinTrailingSilence);
    }

    public SherpaConfig withCleanup(CleanupConfig cleanupConfig) {
        return new SherpaConfig(modelDir, numThreads, provider, punctuationModelDir, cleanupConfig, rule1MinTrailingSilence, rule2MinTrailingSilence);
    }

    public SherpaConfig withSilenceThresholds(float rule1, float rule2) {
        return new SherpaConfig(modelDir, numThreads, provider, punctuationModelDir, cleanupConfig, rule1, rule2);
    }
}
