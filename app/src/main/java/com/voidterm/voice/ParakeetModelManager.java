package com.voidterm.voice;

import android.content.Context;

import com.voidterm.contracts.FileSpec;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages Parakeet TDT v3 ONNX model files, parameterized by quantization.
 * Models are stored in {filesDir}/models/parakeet/.
 *
 * Two quantizations (see {@link ParakeetQuantization}):
 * - int8 (~670 MB): encoder-model.int8.onnx + decoder_joint-model.int8.onnx (inline weights)
 * - fp32 (~2.55 GB): encoder-model.onnx + encoder-model.onnx.data (external) + decoder_joint-model.onnx
 * Shared by both: nemo128.onnx (preprocessor) + vocab.txt (8193 tokens).
 */
public class ParakeetModelManager {

    private static final String MODELS_DIR = "models";
    private static final String PARAKEET_DIR = "parakeet";

    /** Get the parakeet models directory path. */
    public static File getModelDir(Context context) {
        return new File(new File(context.getFilesDir(), MODELS_DIR), PARAKEET_DIR);
    }

    /** True if all files of {@code q} exist with non-zero size. */
    public static boolean isModelComplete(Context context, ParakeetQuantization q) {
        File modelDir = getModelDir(context);
        if (!modelDir.exists()) return false;
        for (String file : q.allFiles()) {
            File f = new File(modelDir, file);
            if (!f.exists() || f.length() == 0) return false;
        }
        return true;
    }

    /** The files to download for {@code q}, as boundary DTOs. */
    public static List<FileSpec> fileSpecs(Context context, ParakeetQuantization q) {
        File modelDir = getModelDir(context);
        List<FileSpec> specs = new ArrayList<>();
        for (String file : q.allFiles()) {
            specs.add(new FileSpec(q.url(file), new File(modelDir, file), file));
        }
        return specs;
    }

    /** Total size on disk of {@code q}'s files, in bytes. */
    public static long getDownloadedSize(Context context, ParakeetQuantization q) {
        File modelDir = getModelDir(context);
        if (!modelDir.exists()) return 0;
        long total = 0;
        for (String file : q.allFiles()) {
            File f = new File(modelDir, file);
            if (f.exists()) total += f.length();
        }
        return total;
    }

    /**
     * Delete only {@code q}'s SPECIFIC files (encoder/decoder/extra). The common files
     * (nemo128.onnx, vocab.txt) are always kept — the other quantization may share them,
     * and they are tiny (~230 KB). Also cleans up any .tmp leftovers.
     */
    public static void deleteModels(Context context, ParakeetQuantization q) {
        File modelDir = getModelDir(context);
        if (!modelDir.exists()) return;
        for (String file : q.specificFiles()) {
            File f = new File(modelDir, file);
            if (f.exists()) f.delete();
        }
        // Only clean up THIS quantization's temp files — never another quantization's
        // in-progress download.
        for (String file : q.specificFiles()) {
            File tmp = new File(modelDir, file + ".tmp");
            if (tmp.exists()) tmp.delete();
        }
    }

}
