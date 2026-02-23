package com.voidterm.voice;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link AudioPreprocessor} voice preprocessing pipeline.
 */
public class AudioPreprocessorTest {

    private static final float DELTA = 1e-4f;

    @Test
    public void process_nullInput_returnsEmptyArray() {
        float[] result = AudioPreprocessor.process(null);
        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    public void process_emptyInput_returnsEmptyArray() {
        float[] result = AudioPreprocessor.process(new float[0]);
        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    public void process_returnsNewArray() {
        float[] input = {0.1f, 0.2f, 0.3f, 0.2f, 0.1f};
        float[] result = AudioPreprocessor.process(input);
        assertTrue(result != input);
    }

    @Test
    public void process_preservesLength() {
        float[] input = new float[16000]; // 1 second at 16kHz
        for (int i = 0; i < input.length; i++) {
            input[i] = (float) Math.sin(2 * Math.PI * 440 * i / 16000.0) * 0.5f;
        }
        float[] result = AudioPreprocessor.process(input);
        assertEquals(input.length, result.length);
    }

    // --- DC Offset Removal ---

    @Test
    public void removeDcOffset_centersSignalAtZero() {
        float[] input = {1.5f, 1.6f, 1.4f, 1.5f, 1.5f};
        float[] result = AudioPreprocessor.removeDcOffset(input);
        // Mean should be ~0
        float sum = 0f;
        for (float v : result) sum += v;
        assertEquals(0f, sum / result.length, DELTA);
    }

    @Test
    public void removeDcOffset_alreadyCentered_noChange() {
        float[] input = {-0.5f, 0.5f, -0.5f, 0.5f};
        float[] result = AudioPreprocessor.removeDcOffset(input);
        assertArrayEquals(input, result, DELTA);
    }

    // --- High-Pass Filter ---

    @Test
    public void highPassFilter_removesConstantDC() {
        // Constant signal (pure DC) should be attenuated to near-zero
        float[] input = new float[1600];
        for (int i = 0; i < input.length; i++) input[i] = 1.0f;
        float[] result = AudioPreprocessor.highPassFilter(input);
        // Last sample should be near zero (DC fully rejected)
        assertEquals(0f, result[result.length - 1], 0.05f);
    }

    @Test
    public void highPassFilter_passesHighFrequencies() {
        // 1kHz sine at 16kHz sample rate — should pass through mostly intact
        float[] input = new float[1600];
        for (int i = 0; i < input.length; i++) {
            input[i] = (float) Math.sin(2 * Math.PI * 1000 * i / 16000.0);
        }
        float[] result = AudioPreprocessor.highPassFilter(input);
        // RMS of output should be close to RMS of input
        float inputRms = rms(input);
        float outputRms = rms(result);
        assertTrue("High freq should pass through, ratio=" + (outputRms / inputRms),
                outputRms / inputRms > 0.9f);
    }

    // --- Pre-Emphasis ---

    @Test
    public void preEmphasis_firstSampleUnchanged() {
        float[] input = {0.5f, 0.3f, 0.7f};
        float[] result = AudioPreprocessor.preEmphasis(input);
        assertEquals(0.5f, result[0], DELTA);
    }

    @Test
    public void preEmphasis_formula() {
        // y[n] = x[n] - 0.97 * x[n-1]
        float[] input = {0.5f, 0.8f, 0.3f};
        float[] result = AudioPreprocessor.preEmphasis(input);
        assertEquals(0.5f, result[0], DELTA);
        assertEquals(0.8f - 0.97f * 0.5f, result[1], DELTA);
        assertEquals(0.3f - 0.97f * 0.8f, result[2], DELTA);
    }

    @Test
    public void preEmphasis_boostsHighFrequencies() {
        // High freq (4kHz) should have higher energy after emphasis than low freq (200Hz)
        float[] lowFreq = new float[1600];
        float[] highFreq = new float[1600];
        for (int i = 0; i < 1600; i++) {
            lowFreq[i] = (float) Math.sin(2 * Math.PI * 200 * i / 16000.0) * 0.5f;
            highFreq[i] = (float) Math.sin(2 * Math.PI * 4000 * i / 16000.0) * 0.5f;
        }
        float lowBoost = rms(AudioPreprocessor.preEmphasis(lowFreq)) / rms(lowFreq);
        float highBoost = rms(AudioPreprocessor.preEmphasis(highFreq)) / rms(highFreq);
        assertTrue("High freq boost (" + highBoost + ") should exceed low freq boost (" + lowBoost + ")",
                highBoost > lowBoost);
    }

    // --- Normalization ---

    @Test
    public void normalizeGain_scalesToTarget() {
        float[] input = {0.0f, 0.3f, -0.45f, 0.2f};
        float[] result = AudioPreprocessor.normalizeGain(input);
        // Peak should be 0.9
        float peak = 0f;
        for (float v : result) peak = Math.max(peak, Math.abs(v));
        assertEquals(0.9f, peak, DELTA);
    }

    @Test
    public void normalizeGain_silence_noChange() {
        float[] input = {0.0001f, -0.0001f, 0.00005f};
        float[] result = AudioPreprocessor.normalizeGain(input);
        assertArrayEquals(input, result, DELTA);
    }

    // --- Stats ---

    @Test
    public void computeStats_emptyInput() {
        AudioPreprocessor.Stats stats = AudioPreprocessor.computeStats(new float[0]);
        assertEquals(0f, stats.rms, DELTA);
        assertEquals(0f, stats.peakAmplitude, DELTA);
        assertEquals(0, stats.sampleCount);
    }

    @Test
    public void computeStats_correctValues() {
        float[] input = {0.3f, -0.5f, 0.4f, -0.2f};
        AudioPreprocessor.Stats stats = AudioPreprocessor.computeStats(input);
        assertEquals(0.5f, stats.peakAmplitude, DELTA);
        assertEquals(4, stats.sampleCount);
        assertTrue(stats.rms > 0f);
        assertTrue(stats.durationSec > 0f);
    }

    // --- PipelineResult (diagnostics) ---

    @Test
    public void processWithDiagnostics_nullInput() {
        AudioPreprocessor.PipelineResult result = AudioPreprocessor.processWithDiagnostics(null);
        assertNotNull(result);
        assertEquals(0, result.output.length);
        assertEquals("empty", result.normAction);
    }

    @Test
    public void processWithDiagnostics_allStagesPresent() {
        float[] input = new float[3200]; // 200ms
        for (int i = 0; i < input.length; i++) {
            input[i] = (float) Math.sin(2 * Math.PI * 440 * i / 16000.0) * 0.3f;
        }
        AudioPreprocessor.PipelineResult result = AudioPreprocessor.processWithDiagnostics(input);
        assertNotNull(result.raw);
        assertNotNull(result.afterDc);
        assertNotNull(result.afterHp);
        assertNotNull(result.afterEmphasis);
        assertNotNull(result.afterNormalization);
        assertNotNull(result.normAction);
        assertEquals(input.length, result.output.length);
    }

    // --- Full pipeline integration ---

    @Test
    public void process_normalizes_sineWave() {
        float[] input = new float[16000];
        for (int i = 0; i < input.length; i++) {
            input[i] = (float) Math.sin(2 * Math.PI * 440 * i / 16000.0) * 0.2f;
        }
        float[] result = AudioPreprocessor.process(input);
        float peak = 0f;
        for (float v : result) peak = Math.max(peak, Math.abs(v));
        // Should be normalized to ~0.9
        assertEquals(0.9f, peak, 0.01f);
    }

    private static float rms(float[] data) {
        float sum = 0f;
        for (float v : data) sum += v * v;
        return (float) Math.sqrt(sum / data.length);
    }
}
