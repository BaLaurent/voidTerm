package com.voidterm.voice;

/**
 * Immutable configuration for the Parakeet TDT (ONNX) transcription engine.
 * Cached in {@link ParakeetEngine} and invalidated on SharedPreferences change,
 * mirroring {@link WhisperConfig}.
 *
 * Two distinct duration concepts must never be confused with the AudioCapture
 * recording ceiling:
 * - {@link #maxWindowSamples} is what fits in ONE encoder pass. Audio longer than
 *   this is split by {@link AudioChunker}; audio at or below runs in a single pass
 *   exactly as before. Clamped to a tested-safe ceiling — raising it past what the
 *   encoder can handle re-introduces the OOM that chunking exists to prevent.
 * - The AudioCapture recording cap is engine-agnostic (audio is captured before the
 *   engine is known) and lives there, not here.
 */
public class ParakeetConfig {

    /** ONNX intra-op thread count for the encoder. 0 = auto (CpuInfo). */
    public final int threadCount;
    /** Max samples per encoder pass. Audio above this is chunked. */
    public final int maxWindowSamples;
    /** Overlap prepended to a fallback (continuous-speech) chunk, for dedup. */
    public final int overlapSamples;
    /** RMS below which a frame counts as a silence (a candidate cut point). */
    public final float silenceThreshold;
    /** Trailing band of a window searched for a silence cut point. */
    public final int searchBandSamples;
    /** Per-encoder-frame emission cap (forward-progress guard in greedy decode). */
    public final int maxTokensPerStep;

    public ParakeetConfig(int threadCount, int maxWindowSamples, int overlapSamples,
                          float silenceThreshold, int searchBandSamples, int maxTokensPerStep) {
        this.threadCount = threadCount;
        this.maxWindowSamples = maxWindowSamples;
        this.overlapSamples = overlapSamples;
        this.silenceThreshold = silenceThreshold;
        this.searchBandSamples = searchBandSamples;
        this.maxTokensPerStep = maxTokensPerStep;
    }

    private static final int SR = AudioCapture.SAMPLE_RATE;

    /** Tested-safe ceiling for a single encoder pass (seconds). */
    public static final int MAX_WINDOW_SEC_CEILING = 30;
    /** Lower bound so a window always holds at least a short utterance (seconds). */
    public static final int MAX_WINDOW_SEC_FLOOR = 10;

    public static final ParakeetConfig DEFAULT = new ParakeetConfig(
            0,            // auto threads
            30 * SR,      // 30s window
            16000,        // 1.0s overlap (fallback only)
            0.005f,       // silence RMS threshold (distinct from AudioCapture's 0.001 metering)
            4 * SR,       // 4s trailing search band
            10            // matches the legacy MAX_TOKENS_PER_STEP constant
    );
}
