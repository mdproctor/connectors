package io.casehub.connectors.voice.sherpa;

import io.casehub.connectors.voice.spi.CleanupConfig;
import io.casehub.connectors.voice.spi.RecognitionStream;
import io.casehub.connectors.voice.spi.StreamingSpeechToText;
import io.casehub.connectors.voice.model.TranscriptionOptions;
import io.casehub.connectors.voice.model.TranscriptionResult;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.Objects;

public final class SherpaOnnxStreamingSpeechToText implements StreamingSpeechToText {

    private final SherpaConfig config;
    private final SherpaLibrary lib;
    private final MemorySegment recognizer;
    private final Arena recognizerArena;
    private final @org.jspecify.annotations.Nullable CleanupConfig cleanupConfig;
    private io.casehub.connectors.voice.spi.StreamingSpeechDenoiserFactory denoiserFactory;
    private java.util.function.BooleanSupplier denoiserEnabled;
    private io.casehub.connectors.voice.spi.VoiceActivityFilterFactory vadFactory;
    private java.util.function.BooleanSupplier vadEnabled;


    private static final String DEFAULT_STREAMING_MODEL = "sherpa-onnx-streaming-zipformer-en-2023-06-26";

    public static SherpaOnnxStreamingSpeechToText withDefaults() {
        Provisioner.ensureNativeLibrary();
        Path modelDir = Provisioner.ensureStreamingSttModel(DEFAULT_STREAMING_MODEL);
        return new SherpaOnnxStreamingSpeechToText(SherpaConfig.defaults(modelDir));
    }

    public static void ensureProvisioned() {
        Provisioner.ensureNativeLibrary();
        Provisioner.ensureStreamingSttModel(DEFAULT_STREAMING_MODEL);
    }

    public SherpaOnnxStreamingSpeechToText(SherpaConfig config) {
        this(config, SherpaLibrary.load());
    }

    SherpaOnnxStreamingSpeechToText(SherpaConfig config, SherpaLibrary lib) {
        this.config = Objects.requireNonNull(config);
        this.lib = lib;
        this.recognizerArena = Arena.ofShared();
        this.recognizer = createRecognizer();
        this.cleanupConfig = resolveCleanup(config, lib);
    }

    @Override
    public RecognitionStream startStream(TranscriptionOptions options) {
        Objects.requireNonNull(options, "options");
        return new SherpaRecognitionStream();
    }

    public SherpaOnnxStreamingSpeechToText withStreamingDenoiser(
            io.casehub.connectors.voice.spi.StreamingSpeechDenoiserFactory factory,
            java.util.function.BooleanSupplier enabled) {
        this.denoiserFactory = factory;
        this.denoiserEnabled = enabled;
        return this;
    }

    public SherpaOnnxStreamingSpeechToText withVoiceActivityFilter(
            io.casehub.connectors.voice.spi.VoiceActivityFilterFactory factory,
            java.util.function.BooleanSupplier enabled) {
        this.vadFactory = factory;
        this.vadEnabled = enabled;
        return this;
    }

    public void close() {
        if (cleanupConfig != null) {
            cleanupConfig.filters().forEach(f -> {
                if (f instanceof AutoCloseable ac) {
                    try { ac.close(); } catch (Exception e) { /* cleanup */ }
                }
            });
        }
        if (recognizer != null && !recognizer.equals(MemorySegment.NULL)) {
            try {
                lib.destroyOnlineRecognizer.invokeExact(recognizer);
            } catch (Throwable t) {
                // native cleanup
            }
        }
        recognizerArena.close();
    }

    public static CleanupConfig defaultFilters(Path punctuationModelDir) {
        return defaultFilters(punctuationModelDir, SherpaLibrary.load());
    }

    static CleanupConfig defaultFilters(Path punctuationModelDir, SherpaLibrary lib) {
        return CleanupConfig.of(
                new CasingFilter(),
                new FillerRemovalFilter(),
                new PunctuationFilter(punctuationModelDir, lib));
    }

    private static CleanupConfig resolveCleanup(SherpaConfig config, SherpaLibrary lib) {
        if (config.cleanupConfig() != null) return config.cleanupConfig();
        if (config.punctuationModelDir() != null) return defaultFilters(config.punctuationModelDir(), lib);
        return null;
    }

    private String applyCleanup(String raw) {
        if (cleanupConfig == null || raw.isBlank()) return raw;
        return cleanupConfig.apply(raw);
    }

    private MemorySegment createRecognizer() {
        MemorySegment configSeg = recognizerArena.allocate(SherpaLayouts.CONFIG_ALLOC_SIZE);
        configSeg.fill((byte) 0);

        configSeg.set(ValueLayout.JAVA_INT, SherpaLayouts.FEAT_SAMPLE_RATE, 16000);
        configSeg.set(ValueLayout.JAVA_INT, SherpaLayouts.FEAT_FEATURE_DIM, 80);

        Path modelDir = config.modelDir();
        configSeg.set(ValueLayout.ADDRESS, SherpaLayouts.ONLINE_TRANSDUCER_ENCODER,
                recognizerArena.allocateFrom(findModel(modelDir, "encoder")));
        configSeg.set(ValueLayout.ADDRESS, SherpaLayouts.ONLINE_TRANSDUCER_DECODER,
                recognizerArena.allocateFrom(findModel(modelDir, "decoder")));
        configSeg.set(ValueLayout.ADDRESS, SherpaLayouts.ONLINE_TRANSDUCER_JOINER,
                recognizerArena.allocateFrom(findModel(modelDir, "joiner")));
        configSeg.set(ValueLayout.ADDRESS, SherpaLayouts.ONLINE_MODEL_TOKENS,
                recognizerArena.allocateFrom(modelDir.resolve("tokens.txt").toString()));
        configSeg.set(ValueLayout.JAVA_INT, SherpaLayouts.ONLINE_MODEL_NUM_THREADS, config.numThreads());
        configSeg.set(ValueLayout.ADDRESS, SherpaLayouts.ONLINE_MODEL_PROVIDER,
                recognizerArena.allocateFrom(config.provider()));

        configSeg.set(ValueLayout.JAVA_INT, SherpaLayouts.ONLINE_ENABLE_ENDPOINT, 1);
        configSeg.set(ValueLayout.JAVA_FLOAT, SherpaLayouts.ONLINE_RULE1_MIN_TRAILING_SILENCE, config.rule1MinTrailingSilence());
        configSeg.set(ValueLayout.JAVA_FLOAT, SherpaLayouts.ONLINE_RULE2_MIN_TRAILING_SILENCE, config.rule2MinTrailingSilence());
        configSeg.set(ValueLayout.JAVA_FLOAT, SherpaLayouts.ONLINE_RULE3_MIN_UTTERANCE_LENGTH, 20.0f);

        MemorySegment rec;
        try {
            rec = (MemorySegment) lib.createOnlineRecognizer.invokeExact(configSeg);
        } catch (Throwable t) {
            throw new SherpaException("Failed to create online recognizer", t);
        }
        if (rec.equals(MemorySegment.NULL)) {
            throw new SherpaException("sherpa-onnx returned null online recognizer — check model paths in " + modelDir);
        }
        return rec;
    }

    private String findModel(Path modelDir, String component) {
        try (var files = java.nio.file.Files.list(modelDir)) {
            var candidates = files
                                     .filter(p -> p.getFileName().toString().contains(component))
                                     .filter(p -> p.toString().endsWith(".onnx"))
                                     .sorted(java.util.Comparator.<Path, Boolean>comparing(
                                             p -> p.getFileName().toString().contains("int8")).reversed())
                                     .toList();
            if (candidates.isEmpty()) {
                throw new SherpaException("No " + component + " model found in " + modelDir);
            }
            return candidates.getFirst().toString();
        } catch (java.io.IOException e) {
            throw new SherpaException("Failed to scan model directory: " + modelDir, e);
        }
    }

    private final class SherpaRecognitionStream implements RecognitionStream {
        private final    MemorySegment                            stream;
        private final    Arena                                    streamArena;
        private final    java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        private volatile boolean                                  closed;
        private final io.casehub.connectors.voice.spi.StreamingSpeechDenoiser denoiser;
        private final io.casehub.connectors.voice.spi.VoiceActivityFilter vadFilter;

        SherpaRecognitionStream() {
            this.denoiser = (denoiserFactory != null) ? denoiserFactory.create() : null;
            this.vadFilter = (vadFactory != null) ? vadFactory.create() : null;
            this.streamArena = Arena.ofShared();
            try {
                this.stream = (MemorySegment) lib.createOnlineStream.invokeExact(recognizer);
            } catch (Throwable t) {
                streamArena.close();
                throw new SherpaException("Failed to create online stream", t);
            }
        }

        @Override
        public void acceptSamples(float[] samples, int sampleRate) {
            if (closed) {throw new IllegalStateException("Stream is closed");}
            float[] processed = samples;
            if (denoiser != null && denoiserEnabled != null && denoiserEnabled.getAsBoolean()) {
                processed = denoiser.processChunk(samples, sampleRate);
            }
            if (vadFilter != null && vadEnabled != null && vadEnabled.getAsBoolean()) {
                processed = vadFilter.filterChunk(processed, sampleRate);
            }
            if (processed.length == 0) { return; }
            lock.lock();
            try (Arena temp = Arena.ofConfined()) {
                MemorySegment samplesSeg = temp.allocateFrom(ValueLayout.JAVA_FLOAT, processed);
                lib.onlineStreamAcceptWaveform.invokeExact(stream, sampleRate, samplesSeg, processed.length);
                decode();
            } catch (SherpaException e) {
                throw e;
            } catch (Throwable t) {
                throw new SherpaException("Failed to accept waveform", t);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean isEndpointDetected() {
            if (closed) {return false;}
            lock.lock();
            try {
                return ((int) lib.isEndpoint.invokeExact(recognizer, stream)) != 0;
            } catch (Throwable t) {
                throw new SherpaException("Failed to check endpoint", t);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public String partialResult() {
            return applyCleanup(readResult());
        }

        @Override
        public TranscriptionResult finalResult() {
            flushWithSilence();
            String text = applyCleanup(readResult());
            return new TranscriptionResult(text, text.isEmpty() ? "" : "en", 1.0);
        }

        @Override
        public TranscriptionResult segmentResult() {
            String text = applyCleanup(readResult());
            reset();
            return new TranscriptionResult(text, text.isEmpty() ? "" : "en", 1.0);
        }

        @Override
        public void reset() {
            if (closed) {throw new IllegalStateException("Stream is closed");}
            lock.lock();
            try {
                lib.resetOnlineStream.invokeExact(recognizer, stream);
            } catch (Throwable t) {
                throw new SherpaException("Failed to reset online stream", t);
            } finally {
                lock.unlock();
            }
        }


        private void flushWithSilence() {
            if (closed) {return;}
            lock.lock();
            try (Arena temp = Arena.ofConfined()) {
                float[]       silence    = new float[16000];
                MemorySegment samplesSeg = temp.allocateFrom(ValueLayout.JAVA_FLOAT, silence);
                lib.onlineStreamAcceptWaveform.invokeExact(stream, 16000, samplesSeg, silence.length);
                decode();
            } catch (Throwable t) {
                // best-effort flush — don't fail the finalResult call
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void close() {
            if (closed) {return;}
            closed = true;
            lock.lock();
            try {
                lib.destroyOnlineStream.invokeExact(stream);
            } catch (Throwable t) {
                // native cleanup
            } finally {
                lock.unlock();
            }
            streamArena.close();
            if (denoiser != null) { denoiser.close(); }
            if (vadFilter != null) { vadFilter.close(); }
        }

        private void decode() {
            try {
                while (((int) lib.isOnlineStreamReady.invokeExact(recognizer, stream)) != 0) {
                    lib.decodeOnlineStream.invokeExact(recognizer, stream);
                }
            } catch (Throwable t) {
                throw new SherpaException("Failed to decode online stream", t);
            }
        }

        private String readResult() {
            if (closed) {return "";}
            lock.lock();
            try {
                MemorySegment result = (MemorySegment) lib.getOnlineStreamResult.invokeExact(recognizer, stream);
                try {
                    MemorySegment textPtr = result.reinterpret(Long.MAX_VALUE).get(ValueLayout.ADDRESS, 0);
                    return textPtr.reinterpret(Long.MAX_VALUE).getString(0);
                } finally {
                    try {lib.destroyOnlineRecognizerResult.invokeExact(result);} catch (Throwable t) { /* cleanup */ }
                }
            } catch (SherpaException e) {
                throw e;
            } catch (Throwable t) {
                throw new SherpaException("Failed to get online stream result", t);
            } finally {
                lock.unlock();
            }
        }
    }
}
