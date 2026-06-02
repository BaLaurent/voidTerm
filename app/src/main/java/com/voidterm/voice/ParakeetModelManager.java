package com.voidterm.voice;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private static final String TAG = "ParakeetModelManager";
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

    public interface ProgressCallback {
        void onProgress(String fileName, int fileIndex, int totalFiles, long bytesDownloaded, long totalBytes);
        void onFileComplete(String fileName, int fileIndex, int totalFiles);
        void onComplete();
        void onError(String error);
    }

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

    /** Sentinel error message used by {@link #download} when {@code cancelFlag} is tripped. */
    public static final String CANCELLED = "Cancelled";

    /**
     * Download all missing model files from HuggingFace, BLOCKING on the caller's
     * thread. Callbacks fire synchronously on that same thread — the caller owns
     * threading and dispatch (e.g. a foreground service routing to a notification).
     *
     * Skips files that already exist with non-zero size (simple resume: completed
     * files are kept, an interrupted file restarts from scratch). Checks
     * {@code cancelFlag} between files and inside the read loop for prompt
     * cancellation; on cancel it reports {@link #CANCELLED} via {@code onError}.
     */
    public static void download(Context context, ProgressCallback callback, AtomicBoolean cancelFlag) {
        try {
            File modelDir = getModelDir(context);
            if (!modelDir.exists() && !modelDir.mkdirs()) {
                callback.onError("Failed to create model directory");
                return;
            }

            int totalFiles = REQUIRED_FILES.length;
            for (int i = 0; i < totalFiles; i++) {
                if (cancelFlag.get()) {
                    callback.onError(CANCELLED);
                    return;
                }
                String fileName = REQUIRED_FILES[i];
                File destFile = new File(modelDir, fileName);

                // Skip already downloaded files
                if (destFile.exists() && destFile.length() > 0) {
                    Log.i(TAG, "Skipping " + fileName + " (already exists, " + destFile.length() + " bytes)");
                    callback.onFileComplete(fileName, i, totalFiles);
                    continue;
                }

                String urlStr = DOWNLOAD_URLS[i];
                Log.i(TAG, "Downloading " + fileName + " from " + urlStr);

                File tempFile = new File(modelDir, fileName + ".tmp");
                final int fileIdx = i;

                try {
                    HttpModelDownloader.download(urlStr, tempFile, cancelFlag, (bytesDownloaded, totalBytes) ->
                            callback.onProgress(fileName, fileIdx, totalFiles, bytesDownloaded, totalBytes));

                    // Atomic rename to prevent partial files
                    if (!tempFile.renameTo(destFile)) {
                        throw new IOException("Failed to rename temp file to " + destFile.getName());
                    }

                    Log.i(TAG, "Downloaded " + fileName + " (" + destFile.length() + " bytes)");
                    callback.onFileComplete(fileName, fileIdx, totalFiles);

                } catch (IOException e) {
                    // Clean up partial download
                    if (tempFile.exists()) tempFile.delete();
                    if (cancelFlag.get()) {
                        callback.onError(CANCELLED);
                        return;
                    }
                    String error = "Failed to download " + fileName + ": " + e.getMessage();
                    Log.e(TAG, error, e);
                    callback.onError(error);
                    return;
                }
            }

            callback.onComplete();

        } catch (Exception e) {
            Log.e(TAG, "Download failed", e);
            callback.onError("Download failed: " + e.getMessage());
        }
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
