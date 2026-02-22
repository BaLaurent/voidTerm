package com.voidterm.voice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Java bridge to whisper.cpp native library via JNI.
 * Handles model lifecycle (load, transcribe, free) with async threading.
 *
 * Thread safety:
 * - contextHandle guarded by contextLock for all reads/writes
 * - isTranscribing uses AtomicBoolean.compareAndSet for safe check-then-act
 * - isLoading prevents concurrent model loads
 * - release() joins any active transcription thread before freeing native context
 */
public class WhisperBridge {

    private static final String TAG = "WhisperBridge";
    private static final String MODELS_DIR = "models";
    private static final int COPY_BUFFER_SIZE = 8192;
    private static final long TRANSCRIPTION_TIMEOUT_MS = 30_000;

    static {
        System.loadLibrary("whisper_jni");
    }

    // Native method declarations — must match JNI signatures exactly
    private native long nativeInit(String modelPath);
    private native String nativeTranscribe(long ctx, float[] audio, String lang);
    private native void nativeFree(long ctx);
    private native boolean nativeIsLoaded(long ctx);
    private native void nativeAbort();

    private final Object contextLock = new Object();
    private long contextHandle = 0;
    private final AtomicBoolean isTranscribing = new AtomicBoolean(false);
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private volatile boolean isDestroyed = false;
    private volatile Thread transcribeThread;
    private volatile Runnable timeoutRunnable;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Callback for async operations.
     */
    public interface Callback {
        void onSuccess(String result);
        void onError(String error);
    }

    /**
     * Load whisper model from assets on a background thread.
     * Copies model from assets/models/ to internal storage on first run.
     * Only one load at a time — concurrent calls are rejected.
     *
     * @param context Android context for asset access
     * @param modelFileName Model file name only (e.g., "ggml-base.bin").
     *                      The file is expected in assets/models/ and stored in {filesDir}/models/.
     * @param callback Result callback (called on main thread)
     */
    public void loadModel(Context context, String modelFileName, Callback callback) {
        if (!isLoading.compareAndSet(false, true)) {
            Log.w(TAG, "Already loading a model, rejecting concurrent call");
            mainHandler.post(() -> callback.onError("Model load already in progress"));
            return;
        }

        new Thread(() -> {
            try {
                File modelsDir = new File(context.getFilesDir(), MODELS_DIR);
                if (!modelsDir.exists()) {
                    modelsDir.mkdirs();
                }

                File modelFile = new File(modelsDir, modelFileName);

                if (!modelFile.exists()) {
                    Log.i(TAG, "Copying model from assets: " + modelFileName);
                    try {
                        copyAssetToFile(context, MODELS_DIR + "/" + modelFileName, modelFile);
                    } catch (IOException e) {
                        isLoading.set(false);
                        Log.e(TAG, "Model file not found in assets or filesDir: " + modelFileName);
                        mainHandler.post(() -> callback.onError("Model file not found: " + modelFileName));
                        return;
                    }
                }

                long handle = nativeInit(modelFile.getAbsolutePath());

                if (handle == 0) {
                    isLoading.set(false);
                    mainHandler.post(() -> callback.onError("Failed to initialize whisper model"));
                    return;
                }

                synchronized (contextLock) {
                    // Free any previously loaded context before replacing
                    if (contextHandle != 0) {
                        nativeFree(contextHandle);
                    }
                    contextHandle = handle;
                }

                isLoading.set(false);
                Log.i(TAG, "Model loaded: " + modelFileName);
                mainHandler.post(() -> callback.onSuccess("Model loaded"));

            } catch (Exception e) {
                isLoading.set(false);
                Log.e(TAG, "Failed to load model: " + modelFileName, e);
                mainHandler.post(() -> callback.onError("Failed to load model: " + e.getMessage()));
            }
        }, "WhisperBridge-ModelLoad").start();
    }

    /**
     * Transcribe audio on a background thread.
     * Only one transcription at a time — concurrent calls are rejected.
     *
     * @param audio PCM float32 audio samples at 16kHz mono
     * @param language Language code (e.g., "en", "fr")
     * @param callback Result callback (called on main thread)
     */
    public void transcribe(float[] audio, String language, Callback callback) {
        if (!isTranscribing.compareAndSet(false, true)) {
            Log.w(TAG, "Already transcribing, rejecting concurrent call");
            mainHandler.post(() -> callback.onError("Transcription already in progress"));
            return;
        }

        long handle;
        synchronized (contextLock) {
            handle = contextHandle;
        }

        if (handle == 0) {
            isTranscribing.set(false);
            Log.e(TAG, "Model not loaded");
            mainHandler.post(() -> callback.onError("Whisper model not loaded"));
            return;
        }

        if (audio == null || audio.length == 0) {
            isTranscribing.set(false);
            mainHandler.post(() -> callback.onError("No audio data"));
            return;
        }

        Runnable watchdog = () -> {
            if (isTranscribing.compareAndSet(true, false)) {
                Log.e(TAG, "Transcription timed out after " + TRANSCRIPTION_TIMEOUT_MS + "ms, aborting native");
                nativeAbort();
                if (!isDestroyed) {
                    callback.onError("Transcription timed out");
                }
            }
        };
        timeoutRunnable = watchdog;

        Thread thread = new Thread(() -> {
            try {
                Log.i(TAG, "Starting transcription: " + audio.length + " samples, lang=" + language);
                long startTime = System.currentTimeMillis();

                String result = nativeTranscribe(handle, audio, language);

                long elapsed = System.currentTimeMillis() - startTime;
                Log.i(TAG, "Transcription completed in " + elapsed + "ms: " +
                    (result != null ? result.length() : 0) + " chars");

                // Cancel watchdog — transcription completed normally
                mainHandler.removeCallbacks(watchdog);

                if (!isTranscribing.compareAndSet(true, false)) {
                    // Watchdog already fired — discard this result
                    Log.w(TAG, "Transcription completed after timeout, discarding result");
                    return;
                }

                if (isDestroyed) return;

                mainHandler.post(() -> {
                    if (result != null && !result.isEmpty()) {
                        callback.onSuccess(result.trim());
                    } else {
                        callback.onError("Empty transcription result");
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Transcription failed", e);
                mainHandler.removeCallbacks(watchdog);
                isTranscribing.set(false);

                if (isDestroyed) return;

                mainHandler.post(() -> callback.onError("Transcription error: " + e.getMessage()));
            }
        }, "WhisperBridge-Transcribe");

        transcribeThread = thread;
        thread.start();
        mainHandler.postDelayed(watchdog, TRANSCRIPTION_TIMEOUT_MS);
    }

    /**
     * Check if the whisper model is loaded and ready.
     */
    public boolean isModelLoaded() {
        synchronized (contextLock) {
            return contextHandle != 0 && nativeIsLoaded(contextHandle);
        }
    }

    /**
     * Release native whisper context and free memory.
     * Waits for any active transcription to complete before freeing.
     * Must be called when the bridge is no longer needed.
     */
    public void release() {
        isDestroyed = true;

        // Cancel any pending timeout watchdog
        Runnable pending = timeoutRunnable;
        if (pending != null) {
            mainHandler.removeCallbacks(pending);
            timeoutRunnable = null;
        }

        // Signal native code to abort, then wait for transcription thread to exit
        nativeAbort();
        Thread thread = transcribeThread;
        if (thread != null && thread.isAlive()) {
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Interrupted while waiting for transcription thread");
            }
        }
        transcribeThread = null;

        synchronized (contextLock) {
            if (contextHandle != 0) {
                nativeFree(contextHandle);
                contextHandle = 0;
                Log.i(TAG, "WhisperBridge released");
            }
        }
    }

    /**
     * Copy an asset file to internal storage.
     */
    private void copyAssetToFile(Context context, String assetPath, File destFile) throws IOException {
        try (InputStream in = context.getAssets().open(assetPath);
             FileOutputStream out = new FileOutputStream(destFile)) {
            byte[] buf = new byte[COPY_BUFFER_SIZE];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
        }
        Log.i(TAG, "Asset copied: " + assetPath + " -> " + destFile.getAbsolutePath() +
                " (" + destFile.length() + " bytes)");
    }
}
