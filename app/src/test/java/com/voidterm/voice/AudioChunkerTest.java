package com.voidterm.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * Deterministic unit tests for {@link AudioChunker} (pure logic, no Android).
 *
 * Test windows are chosen well above MIN_TAIL_SAMPLES (8000) so silence splits yield
 * clean, predictable boundaries instead of being swallowed by the tail-merge guard.
 */
public class AudioChunkerTest {

    private static final int WINDOW = 20000;
    private static final int BAND = 6400;
    private static final int OVERLAP = 1600;
    private static final float THR = 0.01f;

    private static ParakeetConfig cfg() {
        return new ParakeetConfig(0, WINDOW, OVERLAP, THR, BAND, 10);
    }

    private static float[] loud(int n) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = 0.5f;
        return a;
    }

    private static void silence(float[] a, int from, int to) {
        for (int i = from; i < to && i < a.length; i++) a[i] = 0f;
    }

    private static int totalSamples(List<AudioChunker.Chunk> chunks) {
        int sum = 0;
        for (AudioChunker.Chunk c : chunks) sum += c.samples.length;
        return sum;
    }

    @Test
    public void fitsInOneWindow_boundaryBehavior() {
        assertTrue(AudioChunker.fitsInOneWindow(WINDOW, cfg()));
        assertTrue(AudioChunker.fitsInOneWindow(WINDOW - 1, cfg()));
        assertFalse(AudioChunker.fitsInOneWindow(WINDOW + 1, cfg()));
    }

    @Test
    public void shortAudio_singlePass_noCopy() {
        float[] audio = loud(5000);
        List<AudioChunker.Chunk> chunks = AudioChunker.split(audio, cfg());
        assertEquals(1, chunks.size());
        assertSame("short audio must reuse the original array (no copy)", audio, chunks.get(0).samples);
        assertEquals(0, chunks.get(0).overlapSamplesAtStart);
        assertFalse(chunks.get(0).isFallbackSplit);
    }

    @Test
    public void emptyAudio_returnsNoChunks() {
        assertTrue(AudioChunker.split(new float[0], cfg()).isEmpty());
        assertTrue(AudioChunker.split(null, cfg()).isEmpty());
    }

    @Test
    public void silenceSplit_cleanBoundaries_noOverlap_contiguous() {
        // 40000 samples, frame-aligned silence in each window's search band.
        float[] audio = loud(40000);
        silence(audio, 16800, 18400); // in window1 search band [13600, 20000)
        silence(audio, 34400, 36000); // in window2 search band [31200, 37600)

        List<AudioChunker.Chunk> chunks = AudioChunker.split(audio, cfg());

        assertEquals(2, chunks.size());
        for (AudioChunker.Chunk c : chunks) {
            assertEquals("silence cuts carry no overlap", 0, c.overlapSamplesAtStart);
            assertFalse("silence cuts are not fallback", c.isFallbackSplit);
        }
        // Clean (non-overlapping) cuts → samples tile the input exactly.
        assertEquals(40000, totalSamples(chunks));
    }

    @Test
    public void continuousSpeech_fallbackSplits_withOverlap() {
        float[] audio = loud(50000); // no silence anywhere → all hard splits
        List<AudioChunker.Chunk> chunks = AudioChunker.split(audio, cfg());

        assertEquals(3, chunks.size());
        // First chunk starts clean; later chunks overlap back for dedup.
        assertFalse(chunks.get(0).isFallbackSplit);
        assertEquals(0, chunks.get(0).overlapSamplesAtStart);
        for (int i = 1; i < chunks.size(); i++) {
            assertTrue("continuous-speech chunk must be a fallback split", chunks.get(i).isFallbackSplit);
            assertEquals(OVERLAP, chunks.get(i).overlapSamplesAtStart);
        }
        // No chunk exceeds the window (final may be shorter).
        for (AudioChunker.Chunk c : chunks) {
            assertTrue(c.samples.length <= WINDOW);
        }
    }

    @Test
    public void undersizedTail_mergedIntoPrevious() {
        float[] audio = loud(39000); // last segment would be ~2200 samples (< MIN_TAIL 8000)
        List<AudioChunker.Chunk> chunks = AudioChunker.split(audio, cfg());

        // Without the merge this would be 3 chunks; the tiny tail folds into chunk 2.
        assertEquals(2, chunks.size());
        // The merged last chunk is longer than a bare window (it absorbed the tail).
        assertTrue(chunks.get(1).samples.length > WINDOW);
    }

    @Test
    public void findSilenceCut_returnsCenterOfQuietestFrame() {
        float[] audio = loud(20000);
        silence(audio, 8000, 9600); // one frame-aligned silent frame
        int cut = AudioChunker.findSilenceCut(audio, 0, 20000, THR);
        assertEquals(8000 + AudioChunker.FRAME_SAMPLES / 2, cut);
    }

    @Test
    public void findSilenceCut_noSilence_returnsMinusOne() {
        assertEquals(-1, AudioChunker.findSilenceCut(loud(20000), 0, 20000, THR));
    }
}
