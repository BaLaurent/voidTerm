package com.voidterm.voice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The two downloadable quantizations of NVIDIA Parakeet TDT 0.6b v3 (multilingual),
 * from HuggingFace istupakov/parakeet-tdt-0.6b-v3-onnx. They share the preprocessor
 * (nemo128.onnx) and vocab (vocab.txt); only encoder/decoder differ. int8 stores its
 * weights inline (large .onnx); fp32 stores them in an external .data file.
 */
public final class ParakeetQuantization {

    public static final String BASE_URL =
            "https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/main/";

    /** Files shared by every quantization (preprocessor + vocab). */
    public static final String[] COMMON_FILES = {"nemo128.onnx", "vocab.txt"};

    public final String id;            // "int8" / "fp32"
    public final String displayName;
    public final int sizeMb;
    public final String encoderFile;
    public final String decoderFile;
    public final String[] extraFiles;  // {} for int8, {encoder-model.onnx.data} for fp32

    private ParakeetQuantization(String id, String displayName, int sizeMb,
                                 String encoderFile, String decoderFile, String[] extraFiles) {
        this.id = id;
        this.displayName = displayName;
        this.sizeMb = sizeMb;
        this.encoderFile = encoderFile;
        this.decoderFile = decoderFile;
        this.extraFiles = extraFiles;
    }

    public static final ParakeetQuantization INT8 = new ParakeetQuantization(
            "int8", "int8 (recommended)", 670,
            "encoder-model.int8.onnx", "decoder_joint-model.int8.onnx", new String[]{});

    public static final ParakeetQuantization FP32 = new ParakeetQuantization(
            "fp32", "fp32 (heavy, ~2.5GB)", 2555,
            "encoder-model.onnx", "decoder_joint-model.onnx",
            new String[]{"encoder-model.onnx.data"});

    public static final List<ParakeetQuantization> ALL =
            Collections.unmodifiableList(Arrays.asList(INT8, FP32));

    /** All files required for this quantization: common + encoder + decoder + extras. */
    public List<String> allFiles() {
        List<String> files = new ArrayList<>(Arrays.asList(COMMON_FILES));
        files.add(encoderFile);
        files.add(decoderFile);
        files.addAll(Arrays.asList(extraFiles));
        return files;
    }

    /** Files specific to this quantization (encoder + decoder + extras), NOT the common ones. */
    public List<String> specificFiles() {
        List<String> files = new ArrayList<>();
        files.add(encoderFile);
        files.add(decoderFile);
        files.addAll(Arrays.asList(extraFiles));
        return files;
    }

    public String url(String fileName) {
        return BASE_URL + fileName;
    }

    public static ParakeetQuantization byId(String id) {
        for (ParakeetQuantization q : ALL) {
            if (q.id.equals(id)) return q;
        }
        return null;
    }
}
