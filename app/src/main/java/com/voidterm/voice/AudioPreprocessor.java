package com.voidterm.voice;

/**
 * Voice-aware preprocessing pipeline for whisper.cpp input.
 * Minimal, research-backed approach: help whisper without distorting the signal.
 *
 * 1. DC Offset Removal — subtracts mean to center signal at zero
 * 2. High-Pass 80Hz (1st order IIR) — removes subsonics/rumble
 * 3. Pre-Emphasis 0.97 — boosts high frequencies (consonants, sibilants)
 * 4. Peak Normalization → 0.9 — scales to optimal whisper input level
 *
 * All methods are stateless and operate on float[] PCM at 16kHz.
 */
public class AudioPreprocessor {

    private AudioPreprocessor() {}

    private static final int SAMPLE_RATE = AudioCapture.SAMPLE_RATE;

    // Derived from AudioConfig.DEFAULT — single source of truth
    private static final float HP_ALPHA = AudioConfig.DEFAULT.hpAlpha();
    private static final float PRE_EMPHASIS_COEFF = AudioConfig.DEFAULT.preEmphasis;
    private static final float NORM_TARGET = AudioConfig.DEFAULT.normTarget;
    private static final float NORM_SILENCE_THRESHOLD = 0.001f;

    /**
     * Run the full voice preprocessing pipeline with default parameters. Returns a NEW array.
     */
    public static float[] process(float[] pcm) {
        return process(pcm, AudioConfig.DEFAULT);
    }

    /**
     * Run the full voice preprocessing pipeline with custom configuration. Returns a NEW array.
     * Gain is applied AFTER normalization as an output volume control — applying it before
     * normalization can only cause clipping distortion (normalization undoes any non-clipping gain).
     */
    public static float[] process(float[] pcm, AudioConfig config) {
        if (pcm == null || pcm.length == 0) return new float[0];
        float[] result = removeDcOffset(pcm);
        result = highPassFilter(result, config.hpAlpha());
        if (config.preEmphasis > 0f) {
            result = preEmphasis(result, config.preEmphasis);
        }
        result = normalizeGain(result, config.normTarget);
        if (config.inputGain != 1.0f) {
            result = applyGain(result, config.inputGain);
        }
        return result;
    }

    /**
     * Compute stats for a PCM buffer (for debug display).
     */
    public static Stats computeStats(float[] pcm) {
        if (pcm == null || pcm.length == 0) {
            return new Stats(0f, 0f, 0f, 0);
        }
        float sumSquares = 0f;
        float peak = 0f;
        for (float sample : pcm) {
            sumSquares += sample * sample;
            float abs = Math.abs(sample);
            if (abs > peak) peak = abs;
        }
        float rms = (float) Math.sqrt(sumSquares / pcm.length);
        float durationSec = pcm.length / (float) SAMPLE_RATE;
        return new Stats(rms, peak, durationSec, pcm.length);
    }

    /**
     * DC Offset Removal: subtracts the mean of the buffer.
     * Centers signal at zero, removing any DC bias from the microphone.
     */
    static float[] removeDcOffset(float[] input) {
        float sum = 0f;
        for (float sample : input) {
            sum += sample;
        }
        float mean = sum / input.length;

        float[] output = new float[input.length];
        for (int i = 0; i < input.length; i++) {
            output[i] = input[i] - mean;
        }
        return output;
    }

    /**
     * Apply output gain with clamp to ±1.0.
     * Applied AFTER normalization as a volume control — never before, to avoid clipping distortion.
     */
    static float[] applyGain(float[] input, float gain) {
        float[] output = new float[input.length];
        for (int i = 0; i < input.length; i++) {
            output[i] = Math.max(-1.0f, Math.min(1.0f, input[i] * gain));
        }
        return output;
    }

    /**
     * High-pass filter at 80Hz (1st order IIR) with default alpha.
     */
    static float[] highPassFilter(float[] input) {
        return highPassFilter(input, HP_ALPHA);
    }

    /**
     * High-pass filter (1st order IIR) with configurable alpha.
     * y[n] = α * (y[n-1] + x[n] - x[n-1])
     */
    static float[] highPassFilter(float[] input, float alpha) {
        float[] output = new float[input.length];
        float prevX = 0f;
        float prevY = 0f;
        for (int i = 0; i < input.length; i++) {
            prevY = alpha * (prevY + input[i] - prevX);
            prevX = input[i];
            output[i] = prevY;
        }
        return output;
    }

    /**
     * Pre-emphasis filter with default coefficient (0.97).
     */
    static float[] preEmphasis(float[] input) {
        return preEmphasis(input, PRE_EMPHASIS_COEFF);
    }

    /**
     * Pre-emphasis filter: y[n] = x[n] - coeff * x[n-1]
     * Boosts high frequencies to compensate for natural spectral roll-off
     * in human speech. Makes consonants and sibilants more prominent.
     */
    static float[] preEmphasis(float[] input, float coeff) {
        float[] output = new float[input.length];
        output[0] = input[0];
        for (int i = 1; i < input.length; i++) {
            output[i] = input[i] - coeff * input[i - 1];
        }
        return output;
    }

    /**
     * Peak normalization with default target (0.9).
     */
    static float[] normalizeGain(float[] input) {
        return normalizeGain(input, NORM_TARGET);
    }

    /**
     * Peak normalization: scale to target level.
     * Skips true silence (peak < 0.001).
     */
    static float[] normalizeGain(float[] input, float target) {
        float peak = 0f;
        for (float sample : input) {
            float abs = Math.abs(sample);
            if (abs > peak) peak = abs;
        }

        if (peak < NORM_SILENCE_THRESHOLD) {
            float[] output = new float[input.length];
            System.arraycopy(input, 0, output, 0, input.length);
            return output;
        }

        float gain = target / peak;
        float[] output = new float[input.length];
        for (int i = 0; i < input.length; i++) {
            output[i] = input[i] * gain;
        }
        return output;
    }

    /**
     * Run the full pipeline with default config and return per-stage results.
     */
    public static PipelineResult processWithDiagnostics(float[] pcm) {
        return processWithDiagnostics(pcm, AudioConfig.DEFAULT);
    }

    /**
     * Run the full pipeline with custom configuration and return per-stage results.
     * Gain is applied after normalization (output volume control, not pre-processing).
     */
    public static PipelineResult processWithDiagnostics(float[] pcm, AudioConfig config) {
        if (pcm == null || pcm.length == 0) {
            Stats empty = computeStats(new float[0]);
            return new PipelineResult(new float[0], empty, empty, empty, empty, empty, empty, "empty");
        }

        Stats rawStats = computeStats(pcm);

        float[] afterDc = removeDcOffset(pcm);
        Stats dcStats = computeStats(afterDc);

        float[] afterHp = highPassFilter(afterDc, config.hpAlpha());
        Stats hpStats = computeStats(afterHp);

        float[] afterEmph;
        Stats emphStats;
        if (config.preEmphasis > 0f) {
            afterEmph = preEmphasis(afterHp, config.preEmphasis);
            emphStats = computeStats(afterEmph);
        } else {
            afterEmph = afterHp;
            emphStats = hpStats;
        }

        float peak = emphStats.peakAmplitude;
        String normAction;
        if (peak < NORM_SILENCE_THRESHOLD) {
            normAction = "skipped (silence)";
        } else {
            normAction = String.format("x%.2f gain \u2192 peak=%.3f", config.normTarget / peak, config.normTarget);
        }

        float[] afterNorm = normalizeGain(afterEmph, config.normTarget);
        Stats normStats = computeStats(afterNorm);

        float[] result = (config.inputGain != 1.0f)
                ? applyGain(afterNorm, config.inputGain) : afterNorm;
        Stats finalStats = (config.inputGain != 1.0f) ? computeStats(result) : normStats;

        return new PipelineResult(result, rawStats, dcStats, finalStats, hpStats, emphStats, normStats, normAction);
    }

    public static class Stats {
        public final float rms;
        public final float peakAmplitude;
        public final float durationSec;
        public final int sampleCount;

        Stats(float rms, float peakAmplitude, float durationSec, int sampleCount) {
            this.rms = rms;
            this.peakAmplitude = peakAmplitude;
            this.durationSec = durationSec;
            this.sampleCount = sampleCount;
        }
    }

    public static class PipelineResult {
        public final float[] output;
        public final Stats raw;
        public final Stats afterDc;
        public final Stats afterGain;
        public final Stats afterHp;
        public final Stats afterEmphasis;
        public final Stats afterNormalization;
        public final String normAction;

        PipelineResult(float[] output, Stats raw, Stats afterDc, Stats afterGain, Stats afterHp,
                       Stats afterEmphasis, Stats afterNormalization, String normAction) {
            this.output = output;
            this.raw = raw;
            this.afterDc = afterDc;
            this.afterGain = afterGain;
            this.afterHp = afterHp;
            this.afterEmphasis = afterEmphasis;
            this.afterNormalization = afterNormalization;
            this.normAction = normAction;
        }
    }
}
