package com.voidterm.voice;

import android.content.Context;

import com.voidterm.contracts.FileSpec;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages Parakeet TDT v3 ONNX model files.
 * Models are stored in {filesDir}/models/parakeet/.
 *
 * Required files (~534 MB total, int8 quantized):
 * - nemo128.onnx (~4 MB) — audio preprocessor
 * - encoder-model.int8.onnx (~150 MB) — Conformer encoder
 * - decoder_joint-model.int8.onnx (~380 MB) — TDT transducer decoder+joint
 * - vocab.txt (~100 KB) — token vocabulary (8193 tokens)
 */
public class ParakeetModelManager {

    private static final String MODELS_DIR = "models";
    private static final String PARAKEET_DIR = "parakeet";
    static final String[] REQUIRED_FILES = {
            "nemo128.onnx",
            "encoder-model.int8.onnx",
            "decoder_joint-model.int8.onnx",
            "vocab.txt"
    };

    private static final String HF_BASE_URL =
            "https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/main/";

    private static final String[] DOWNLOAD_URLS = {
            HF_BASE_URL + "nemo128.onnx",
            HF_BASE_URL + "encoder-model.int8.onnx",
            HF_BASE_URL + "decoder_joint-model.int8.onnx",
            HF_BASE_URL + "vocab.txt"
    };

    /** Check if all required model files exist. */
    public static boolean isModelComplete(Context context) {
        File modelDir = getModelDir(context);
        if (!modelDir.exists()) return false;
        for (String file : REQUIRED_FILES) {
            File f = new File(modelDir, file);
            if (!f.exists() || f.length() == 0) return false;
        }
        return true;
    }

    /** Get the parakeet models directory path. */
    public static File getModelDir(Context context) {
        return new File(new File(context.getFilesDir(), MODELS_DIR), PARAKEET_DIR);
    }

    /** The files to download for Parakeet, as boundary DTOs. */
    public static List<FileSpec> fileSpecs(Context context) {
        File modelDir = getModelDir(context);
        List<FileSpec> specs = new ArrayList<>();
        for (int i = 0; i < REQUIRED_FILES.length; i++) {
            specs.add(new FileSpec(DOWNLOAD_URLS[i], new File(modelDir, REQUIRED_FILES[i]), REQUIRED_FILES[i]));
        }
        return specs;
    }

    /** Get total size of downloaded model files in bytes. */
    public static long getDownloadedSize(Context context) {
        File modelDir = getModelDir(context);
        if (!modelDir.exists()) return 0;
        long total = 0;
        for (String file : REQUIRED_FILES) {
            File f = new File(modelDir, file);
            if (f.exists()) total += f.length();
        }
        return total;
    }

    /** Delete all downloaded model files. */
    public static void deleteModels(Context context) {
        File modelDir = getModelDir(context);
        if (!modelDir.exists()) return;
        for (String file : REQUIRED_FILES) {
            File f = new File(modelDir, file);
            if (f.exists()) f.delete();
        }
        // Also clean up any temp files
        File[] temps = modelDir.listFiles((dir, name) -> name.endsWith(".tmp"));
        if (temps != null) {
            for (File t : temps) t.delete();
        }
    }
}
