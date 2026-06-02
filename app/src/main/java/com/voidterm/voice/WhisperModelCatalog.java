package com.voidterm.voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Static catalog of downloadable Whisper ggml models (HuggingFace ggerganov/whisper.cpp). */
public final class WhisperModelCatalog {

    public static final String BASE_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    public static final class WhisperModel {
        public final String id;          // e.g. "base", "base.en", "base-q5_1"
        public final String fileName;    // e.g. "ggml-base.bin"
        public final String displayName; // e.g. "base", "base.en (q5_1)"
        public final int sizeMb;
        public final String family;      // tiny|base|small|medium|large
        public final boolean quantized;
        public final boolean englishOnly;

        WhisperModel(String fileName, String displayName, int sizeMb, String family,
                     boolean quantized, boolean englishOnly) {
            this.fileName = fileName;
            this.id = fileName.substring("ggml-".length(), fileName.length() - ".bin".length());
            this.displayName = displayName;
            this.sizeMb = sizeMb;
            this.family = family;
            this.quantized = quantized;
            this.englishOnly = englishOnly;
        }

        public String url() { return BASE_URL + fileName; }
    }

    public static final List<WhisperModel> ALL;

    private static void add(List<WhisperModel> l, String file, String label, int mb,
                            String family, boolean q, boolean en) {
        l.add(new WhisperModel(file, label, mb, family, q, en));
    }

    static {
        List<WhisperModel> l = new ArrayList<>();
        // tiny
        add(l, "ggml-tiny.bin", "tiny", 78, "tiny", false, false);
        add(l, "ggml-tiny.en.bin", "tiny.en", 78, "tiny", false, true);
        add(l, "ggml-tiny-q5_1.bin", "tiny (q5_1)", 32, "tiny", true, false);
        add(l, "ggml-tiny-q8_0.bin", "tiny (q8_0)", 44, "tiny", true, false);
        add(l, "ggml-tiny.en-q5_1.bin", "tiny.en (q5_1)", 32, "tiny", true, true);
        add(l, "ggml-tiny.en-q8_0.bin", "tiny.en (q8_0)", 44, "tiny", true, true);
        // base
        add(l, "ggml-base.bin", "base", 148, "base", false, false);
        add(l, "ggml-base.en.bin", "base.en", 148, "base", false, true);
        add(l, "ggml-base-q5_1.bin", "base (q5_1)", 60, "base", true, false);
        add(l, "ggml-base-q8_0.bin", "base (q8_0)", 82, "base", true, false);
        // small
        add(l, "ggml-small.bin", "small", 488, "small", false, false);
        add(l, "ggml-small.en.bin", "small.en", 488, "small", false, true);
        add(l, "ggml-small-q5_1.bin", "small (q5_1)", 190, "small", true, false);
        add(l, "ggml-small-q8_0.bin", "small (q8_0)", 264, "small", true, false);
        add(l, "ggml-small.en-q5_1.bin", "small.en (q5_1)", 190, "small", true, true);
        add(l, "ggml-small.en-q8_0.bin", "small.en (q8_0)", 264, "small", true, true);
        // medium
        add(l, "ggml-medium.bin", "medium", 1530, "medium", false, false);
        add(l, "ggml-medium.en.bin", "medium.en", 1530, "medium", false, true);
        add(l, "ggml-medium-q5_0.bin", "medium (q5_0)", 539, "medium", true, false);
        add(l, "ggml-medium-q8_0.bin", "medium (q8_0)", 823, "medium", true, false);
        add(l, "ggml-medium.en-q5_0.bin", "medium.en (q5_0)", 539, "medium", true, true);
        add(l, "ggml-medium.en-q8_0.bin", "medium.en (q8_0)", 823, "medium", true, true);
        // large
        add(l, "ggml-large-v1.bin", "large-v1", 3090, "large", false, false);
        add(l, "ggml-large-v2.bin", "large-v2", 3090, "large", false, false);
        add(l, "ggml-large-v2-q5_0.bin", "large-v2 (q5_0)", 1080, "large", true, false);
        add(l, "ggml-large-v2-q8_0.bin", "large-v2 (q8_0)", 1660, "large", true, false);
        add(l, "ggml-large-v3.bin", "large-v3", 3100, "large", false, false);
        add(l, "ggml-large-v3-q5_0.bin", "large-v3 (q5_0)", 1080, "large", true, false);
        add(l, "ggml-large-v3-turbo.bin", "large-v3-turbo", 1620, "large", false, false);
        add(l, "ggml-large-v3-turbo-q5_0.bin", "large-v3-turbo (q5_0)", 574, "large", true, false);
        add(l, "ggml-large-v3-turbo-q8_0.bin", "large-v3-turbo (q8_0)", 874, "large", true, false);
        ALL = Collections.unmodifiableList(l);
    }

    /** Ordered list of family keys for the accordion UI. */
    public static final String[] FAMILIES = {"tiny", "base", "small", "medium", "large"};

    public static WhisperModel byFileName(String fileName) {
        for (WhisperModel m : ALL) if (m.fileName.equals(fileName)) return m;
        return null;
    }

    public static WhisperModel byId(String id) {
        for (WhisperModel m : ALL) if (m.id.equals(id)) return m;
        return null;
    }

    private WhisperModelCatalog() {}
}
