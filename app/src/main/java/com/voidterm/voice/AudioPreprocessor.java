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

    // High-pass filter: 1st order IIR at 80Hz
    // α = 1 / (1 + 2π * fc / fs), fc=80Hz, fs=16000Hz
    private static final float HP_ALPHA = 0.9695f;

    // Pre-emphasis coefficient (standard for speech recognition)
    private static final float PRE_EMPHASIS_COEFF = 0.97f;

    // Normalization target
    private static final float NORM_TARGET = 0.9f;
    private static final float NORM_SILENCE_THRESHOLD = 0.001f;

    /**
     * Run the full voice preprocessing pipeline. Returns a NEW array.
     */
    public static float[] process(float[] pcm) {
        if (pcm == null || pcm.length == 0) return new float[0];
        float[] result = removeDcOffset(pcm);
        result = highPassFilter(result);
        result = preEmphasis(result);
        result = normalizeGain(result);
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
     * High-pass filter at 80Hz (1st order IIR).
     * Removes subsonics, rumble, and handling noise.
     * y[n] = α * (y[n-1] + x[n] - x[n-1])
     */
    static float[] highPassFilter(float[] input) {
        float[] output = new float[input.length];
        float prevX = 0f;
        float prevY = 0f;
        for (int i = 0; i < input.length; i++) {
            prevY = HP_ALPHA * (prevY + input[i] - prevX);
            prevX = input[i];
            output[i] = prevY;
        }
        return output;
    }

    /**
     * Pre-emphasis filter: y[n] = x[n] - 0.97 * x[n-1]
     * Boosts high frequencies to compensate for natural spectral roll-off
     * in human speech. Makes consonants and sibilants more prominent.
     */
    static float[] preEmphasis(float[] input) {
        float[] output = new float[input.length];
        output[0] = input[0];
        for (int i = 1; i < input.length; i++) {
            output[i] = input[i] - PRE_EMPHASIS_COEFF * input[i - 1];
        }
        return output;
    }

    /**
     * Peak normalization: scale to NORM_TARGET (0.9).
     * Skips true silence (peak < 0.001).
     */
    static float[] normalizeGain(float[] input) {
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

        float gain = NORM_TARGET / peak;
        float[] output = new float[input.length];
        for (int i = 0; i < input.length; i++) {
            output[i] = input[i] * gain;
        }
        return output;
    }

    /**
     * Run the full pipeline and return per-stage results for debug analysis.
     */
    public static PipelineResult processWithDiagnostics(float[] pcm) {
        if (pcm == null || pcm.length == 0) {
            Stats empty = computeStats(new float[0]);
            return new PipelineResult(new float[0], empty, empty, empty, empty, empty, "empty");
        }

        Stats rawStats = computeStats(pcm);

        float[] afterDc = removeDcOffset(pcm);
        Stats dcStats = computeStats(afterDc);

        float[] afterHp = highPassFilter(afterDc);
        Stats hpStats = computeStats(afterHp);

        float[] afterEmph = preEmphasis(afterHp);
        Stats emphStats = computeStats(afterEmph);

        float peak = emphStats.peakAmplitude;
        String normAction;
        if (peak < NORM_SILENCE_THRESHOLD) {
            normAction = "skipped (silence)";
        } else {
            normAction = String.format("x%.2f gain \u2192 peak=%.3f", NORM_TARGET / peak, NORM_TARGET);
        }

        float[] result = normalizeGain(afterEmph);
        Stats finalStats = computeStats(result);

        return new PipelineResult(result, rawStats, dcStats, hpStats, emphStats, finalStats, normAction);
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
        public final Stats afterHp;
        public final Stats afterEmphasis;
        public final Stats afterNormalization;
        public final String normAction;

        PipelineResult(float[] output, Stats raw, Stats afterDc, Stats afterHp,
                       Stats afterEmphasis, Stats afterNormalization, String normAction) {
            this.output = output;
            this.raw = raw;
            this.afterDc = afterDc;
            this.afterHp = afterHp;
            this.afterEmphasis = afterEmphasis;
            this.afterNormalization = afterNormalization;
            this.normAction = normAction;
        }
    }
}
