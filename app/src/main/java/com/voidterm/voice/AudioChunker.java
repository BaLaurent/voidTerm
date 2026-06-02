package com.voidterm.voice;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits long PCM audio into inference-sized windows for the Parakeet engine.
 *
 * Policy: audio that fits in one encoder pass ({@link ParakeetConfig#maxWindowSamples})
 * is returned as a single chunk — identical to the pre-chunking behavior. Only audio
 * that overflows is split, preferably at a SILENCE (the acoustic equivalent of a
 * sentence boundary) so words are not cut mid-stream. When a window has no silence
 * (continuous speech), it hard-splits and overlaps the next chunk so the duplicated
 * transcript region can be de-duplicated downstream.
 *
 * Pure and stateless — deterministically unit-testable (see AudioChunkerTest), mirroring
 * the design of KeyGestureDetector.
 */
public final class AudioChunker {

    private AudioChunker() {}

    /** A window of audio to transcribe independently. */
    public static final class Chunk {
        public final float[] samples;
        /** Leading samples shared with the previous chunk (0 if cut at a silence). */
        public final int overlapSamplesAtStart;
        /** True when produced by a continuous-speech hard split → dedup leading words. */
        public final boolean isFallbackSplit;

        Chunk(float[] samples, int overlapSamplesAtStart, boolean isFallbackSplit) {
            this.samples = samples;
            this.overlapSamplesAtStart = overlapSamplesAtStart;
            this.isFallbackSplit = isFallbackSplit;
        }
    }

    /** RMS frame size for silence detection (100ms at 16kHz). */
    static final int FRAME_SAMPLES = AudioCapture.SAMPLE_RATE / 10; // 1600

    /**
     * Minimum size for a trailing chunk; a shorter tail is merged into the previous
     * chunk. Mirrors the 0.5s minimum-duration hallucination guard so a tiny tail
     * cannot slip past it and hallucinate.
     */
    static final int MIN_TAIL_SAMPLES = AudioCapture.SAMPLE_RATE / 2; // 8000

    /** True when the whole clip fits in one encoder pass (no chunking needed). */
    public static boolean fitsInOneWindow(int sampleCount, ParakeetConfig cfg) {
        return sampleCount <= cfg.maxWindowSamples;
    }

    /** Mutable boundary used while planning cuts; sliced into Chunks at the end. */
    private static final class Seg {
        int start, end, overlap;
        boolean fallback;
        Seg(int start, int end, int overlap, boolean fallback) {
            this.start = start; this.end = end; this.overlap = overlap; this.fallback = fallback;
        }
    }

    public static List<Chunk> split(float[] audio, ParakeetConfig cfg) {
        List<Chunk> chunks = new ArrayList<>();
        if (audio == null || audio.length == 0) return chunks;
        if (fitsInOneWindow(audio.length, cfg)) {
            chunks.add(new Chunk(audio, 0, false));
            return chunks;
        }

        int n = audio.length;
        List<Seg> segs = new ArrayList<>();
        int pos = 0;
        int pendingOverlap = 0;
        boolean pendingFallback = false;

        while (pos < n) {
            int windowEnd = Math.min(pos + cfg.maxWindowSamples, n);
            if (windowEnd == n) {
                segs.add(new Seg(pos, n, pendingOverlap, pendingFallback));
                break;
            }

            int searchStart = Math.max(pos, windowEnd - cfg.searchBandSamples);
            int cut = findSilenceCut(audio, searchStart, windowEnd, cfg.silenceThreshold);

            if (cut > pos) {
                // Clean cut at a silence — both sides are clean, no overlap/dedup.
                segs.add(new Seg(pos, cut, pendingOverlap, pendingFallback));
                pendingOverlap = 0;
                pendingFallback = false;
                pos = cut;
            } else {
                // Continuous speech — hard-split, overlap the next chunk for dedup.
                int hardCut = pos + cfg.maxWindowSamples;
                segs.add(new Seg(pos, hardCut, pendingOverlap, pendingFallback));
                int overlap = Math.min(cfg.overlapSamples, hardCut - pos - 1); // guarantee progress
                pendingOverlap = overlap;
                pendingFallback = true;
                pos = hardCut - overlap;
            }
        }

        // Merge an undersized trailing chunk into the previous one so it cannot
        // slip past the minimum-duration hallucination guard.
        if (segs.size() >= 2) {
            Seg last = segs.get(segs.size() - 1);
            if (last.end - last.start < MIN_TAIL_SAMPLES) {
                segs.get(segs.size() - 2).end = last.end;
                segs.remove(segs.size() - 1);
            }
        }

        for (Seg s : segs) {
            chunks.add(new Chunk(slice(audio, s.start, s.end), s.overlap, s.fallback));
        }
        return chunks;
    }

    /**
     * Find the lowest-RMS frame below {@code silenceThreshold} in [searchStart, searchEnd),
     * returning its center sample index, or -1 if no frame is quiet enough.
     */
    static int findSilenceCut(float[] audio, int searchStart, int searchEnd, float silenceThreshold) {
        int bestIdx = -1;
        float bestRms = Float.MAX_VALUE;
        for (int frameStart = searchStart; frameStart + FRAME_SAMPLES <= searchEnd; frameStart += FRAME_SAMPLES) {
            float rms = frameRms(audio, frameStart, FRAME_SAMPLES);
            if (rms < silenceThreshold && rms < bestRms) {
                bestRms = rms;
                bestIdx = frameStart + FRAME_SAMPLES / 2;
            }
        }
        return bestIdx;
    }

    private static float frameRms(float[] a, int start, int count) {
        int end = Math.min(start + count, a.length);
        int n = end - start;
        if (n <= 0) return 0f;
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) a[i] * a[i];
        }
        return (float) Math.sqrt(sum / n);
    }

    private static float[] slice(float[] a, int start, int end) {
        float[] out = new float[end - start];
        System.arraycopy(a, start, out, 0, end - start);
        return out;
    }
}
