package com.voidterm.voice;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
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
    private static final long TRANSCRIPTION_TIMEOUT_MS = 120_000;

    static {
        boolean fp16Loaded = false;
        if (isArm64Fp16Supported()) {
            try {
                System.loadLibrary("whisper_jni_v8fp16");
                fp16Loaded = true;
                Log.i(TAG, "Loaded FP16-optimized native library");
            } catch (UnsatisfiedLinkError e) {
                Log.w(TAG, "FP16 library not available, falling back to default", e);
            }
        }
        if (!fp16Loaded) {
            System.loadLibrary("whisper_jni");
            Log.i(TAG, "Loaded default native library");
        }
    }

    private static boolean isArm64Fp16Supported() {
        if (!Build.SUPPORTED_ABIS[0].equals("arm64-v8a")) {
            return false;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Features") && line.contains("fphp")) {
                    return true;
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not read /proc/cpuinfo for FP16 detection", e);
        }
        return false;
    }

    // Native method declarations — must match JNI signatures exactly
    private native long nativeInit(String modelPath, boolean useGpu);
    private native String nativeTranscribe(long ctx, float[] audio, String lang, int nThreads,
                                              boolean translate, String initialPrompt,
                                              float temperature, boolean useBeamSearch,
                                              int beamSize, boolean suppressNonSpeech,
                                              boolean proportionalContext, boolean streaming);
    private native void nativeFree(long ctx);
    private native boolean nativeIsLoaded(long ctx);
    private native void nativeAbort();
    private native String nativeGetSystemInfo();

    /** Public abort for external callers (e.g. VoiceInputManager cancel during streaming). */
    public void abort() {
        nativeAbort();
    }

    private final int preferredThreadCount = CpuInfo.getPreferredThreadCount();

    private final Object contextLock = new Object();
    private long contextHandle = 0;
    private final AtomicBoolean isTranscribing = new AtomicBoolean(false);
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private volatile boolean isDestroyed = false;
    private volatile Thread transcribeThread;
    private volatile Runnable timeoutRunnable;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile Callback currentStreamingCallback;

    private final List<String> logBuffer = new ArrayList<>();

    private void bufLog(String msg) {
        String entry = System.currentTimeMillis() + " " + msg;
        Log.i(TAG, msg);
        synchronized (logBuffer) {
            logBuffer.add(entry);
        }
    }

    private void bufErr(String msg) {
        String entry = System.currentTimeMillis() + " ERROR: " + msg;
        Log.e(TAG, msg);
        synchronized (logBuffer) {
            logBuffer.add(entry);
        }
    }

    /**
     * Get collected diagnostic logs and clear the buffer.
     */
    public String getAndClearLogs() {
        synchronized (logBuffer) {
            StringBuilder sb = new StringBuilder();
            for (String line : logBuffer) {
                sb.append(line).append('\n');
            }
            logBuffer.clear();
            return sb.toString();
        }
    }

    /**
     * Callback type alias — delegates to TranscriptionEngine.Callback
     * so WhisperEngine adapter doesn't need to bridge two identical interfaces.
     */
    public interface Callback extends TranscriptionEngine.Callback {}

    /**
     * Called from JNI (streaming_segment_callback) on the transcription thread.
     * Forwards accumulated text to the current callback on the main thread.
     */
    @SuppressWarnings("unused") // Called from native code via JNI
    private void onNativeSegment(String accumulatedText) {
        Callback cb = currentStreamingCallback;
        if (cb != null && !isDestroyed) {
            mainHandler.post(() -> cb.onPartialResult(accumulatedText));
        }
    }

    /**
     * Load whisper model from assets on a background thread.
     * Copies model from assets/models/ to internal storage on first run.
     * Only one load at a time — concurrent calls are rejected.
     *
     * @param context Android context for asset access
     * @param modelFileName Model file name only (e.g., "ggml-base.bin").
     *                      The file is expected in assets/models/ and stored in {filesDir}/models/.
     * @param useGpu Whether to enable GPU (Vulkan) acceleration
     * @param callback Result callback (called on main thread)
     */
    public void loadModel(Context context, String modelFileName, boolean useGpu, Callback callback) {
        if (isTranscribing.get()) {
            Log.w(TAG, "Cannot load model while transcribing");
            mainHandler.post(() -> callback.onError("Cannot load model during transcription"));
            return;
        }

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
                bufLog("Model file: " + modelFile.getAbsolutePath());

                if (!modelFile.exists()) {
                    bufLog("Copying model from assets: " + modelFileName);
                    try {
                        copyAssetToFile(context, MODELS_DIR + "/" + modelFileName, modelFile);
                    } catch (IOException e) {
                        isLoading.set(false);
                        bufErr("Model file not found in assets or filesDir: " + modelFileName);
                        mainHandler.post(() -> callback.onError("Model file not found: " + modelFileName));
                        return;
                    }
                }

                bufLog("Model size: " + (modelFile.length() / 1024 / 1024) + " MB");

                mainHandler.post(() -> callback.onProgress("Loading model...", 10));

                bufLog("Using GPU: " + useGpu + ", threads: " + preferredThreadCount);

                long loadStart = System.currentTimeMillis();
                long handle = nativeInit(modelFile.getAbsolutePath(), useGpu);
                long loadTime = System.currentTimeMillis() - loadStart;

                if (handle == 0) {
                    isLoading.set(false);
                    bufErr("nativeInit returned 0 (failed) after " + loadTime + "ms");
                    mainHandler.post(() -> callback.onError("Failed to initialize whisper model"));
                    return;
                }

                bufLog("nativeInit OK in " + loadTime + "ms");

                synchronized (contextLock) {
                    // Re-check under lock to prevent TOCTOU: a transcribe() call
                    // could have started between the outer isTranscribing check and here
                    if (isTranscribing.get()) {
                        nativeFree(handle);
                        isLoading.set(false);
                        bufErr("Transcription started during model load — aborting load");
                        mainHandler.post(() -> callback.onError("Cannot load model during transcription"));
                        return;
                    }
                    if (contextHandle != 0) {
                        nativeFree(contextHandle);
                    }
                    contextHandle = handle;
                }

                // Query system info (backends, SIMD flags)
                try {
                    String sysInfo = nativeGetSystemInfo();
                    bufLog(sysInfo);
                } catch (Exception e) {
                    bufErr("nativeGetSystemInfo failed: " + e.getMessage());
                }

                if (isDestroyed) {
                    isLoading.set(false);
                    return;
                }

                mainHandler.post(() -> callback.onProgress("Model ready", 100));

                isLoading.set(false);
                bufLog("Model loaded: " + modelFileName);
                mainHandler.post(() -> callback.onSuccess("Model loaded"));

            } catch (Exception e) {
                isLoading.set(false);
                bufErr("Failed to load model: " + e.getMessage());
                mainHandler.post(() -> callback.onError("Failed to load model: " + e.getMessage()));
            }
        }, "WhisperBridge-ModelLoad").start();
    }

    /**
     * Transcribe audio on a background thread.
     * Only one transcription at a time — concurrent calls are rejected.
     *
     * @param audio PCM float32 audio samples at 16kHz mono
     * @param config Transcription configuration (language, translate, beam search, etc.)
     * @param callback Result callback (called on main thread)
     */
    public void transcribe(float[] audio, WhisperConfig config, Callback callback) {
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

        int threadCount = config.threadCount > 0 ? config.threadCount : preferredThreadCount;

        bufLog("Transcription start: " + audio.length + " samples ("
                + String.format("%.1f", audio.length / 16000f) + "s), lang=" + config.language
                + ", translate=" + config.translate + ", threads=" + threadCount
                + ", streaming=" + config.streaming);

        if (config.streaming) {
            currentStreamingCallback = callback;
        }

        Runnable watchdog = () -> {
            if (isTranscribing.compareAndSet(true, false)) {
                bufErr("Transcription timed out after " + TRANSCRIPTION_TIMEOUT_MS + "ms");
                nativeAbort();
                if (!isDestroyed) {
                    callback.onError("Transcription timed out");
                }
            }
        };
        timeoutRunnable = watchdog;

        Thread thread = new Thread(() -> {
            try {
                long startTime = System.currentTimeMillis();

                String prompt = (config.initialPrompt != null && !config.initialPrompt.isEmpty())
                        ? config.initialPrompt : null;
                String result = nativeTranscribe(handle, audio, config.language, threadCount,
                        config.translate, prompt, config.temperature,
                        config.useBeamSearch, config.beamSize, config.suppressNonSpeech,
                        config.useProportionalContext, config.streaming);

                long elapsed = System.currentTimeMillis() - startTime;
                bufLog("nativeTranscribe returned in " + elapsed + "ms, result="
                        + (result != null ? result.length() + " chars" : "null"));

                // Cancel watchdog — transcription completed normally
                mainHandler.removeCallbacks(watchdog);

                currentStreamingCallback = null;

                if (!isTranscribing.compareAndSet(true, false)) {
                    bufErr("Transcription completed after timeout, discarding");
                    return;
                }

                if (isDestroyed) {
                    mainHandler.post(() -> callback.onError("WhisperBridge destroyed during transcription"));
                    return;
                }

                mainHandler.post(() -> {
                    if (result == null) {
                        bufErr("nativeTranscribe returned null");
                        callback.onError("Transcription failed");
                    } else if (result.trim().isEmpty()) {
                        bufLog("No speech detected (empty result)");
                        callback.onError("No speech detected");
                    } else {
                        bufLog("Result: " + result.trim());
                        callback.onSuccess(result.trim());
                    }
                });

            } catch (Exception e) {
                bufErr("Transcription exception: " + e.getMessage());
                currentStreamingCallback = null;
                mainHandler.removeCallbacks(watchdog);
                isTranscribing.set(false);

                if (isDestroyed) {
                    mainHandler.post(() -> callback.onError("WhisperBridge destroyed during transcription"));
                    return;
                }

                mainHandler.post(() -> callback.onError("Transcription error: " + e.getMessage()));
            }
        }, "WhisperBridge-Transcribe");

        transcribeThread = thread;
        thread.start();
        mainHandler.postDelayed(watchdog, TRANSCRIPTION_TIMEOUT_MS);
    }

    /**
     * Synchronous benchmark: transcribe audio with minimal greedy params.
     * Used by DeviceProfiler to measure device performance after model load.
     * Returns elapsed time in ms, or -1 on error.
     *
     * Package-private — only called from DeviceProfiler on a background thread.
     */
    long benchmarkTranscribe(float[] audio, int threadCount) {
        if (!isTranscribing.compareAndSet(false, true)) {
            Log.w(TAG, "Cannot benchmark while transcribing");
            return -1;
        }

        long handle;
        synchronized (contextLock) {
            handle = contextHandle;
        }

        if (handle == 0) {
            isTranscribing.set(false);
            Log.e(TAG, "Cannot benchmark: model not loaded");
            return -1;
        }

        // Assign transcribeThread so release() can join this thread if called
        // during the benchmark (prevents use-after-free on nativeFree)
        transcribeThread = Thread.currentThread();
        try {
            long start = System.currentTimeMillis();
            nativeTranscribe(handle, audio, "en", threadCount,
                    false, null, 0.0f, false, 5, false, true, false);
            long elapsed = System.currentTimeMillis() - start;
            return elapsed;
        } catch (Exception e) {
            Log.e(TAG, "Benchmark transcription failed", e);
            return -1;
        } finally {
            transcribeThread = null;
            isTranscribing.set(false);
        }
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

        if (thread != null && thread.isAlive()) {
            Log.w(TAG, "Transcription thread still alive after join — skipping nativeFree to avoid use-after-free");
            return;
        }

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
