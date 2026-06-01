package com.voidterm.voice;

import android.content.Context;

/**
 * Abstraction for speech-to-text backends (whisper.cpp, Parakeet TDT, etc.).
 * Each implementation manages its own model lifecycle and configuration.
 *
 * Implementations read their engine-specific config from SharedPreferences
 * internally — callers only provide audio data.
 */
public interface TranscriptionEngine {

    interface Callback {
        void onSuccess(String result);
        void onError(String error);
        default void onProgress(String phase, int percent) {}
        default void onPartialResult(String accumulatedText) {}
    }

    /** Load the engine's model asynchronously. Callback fires on main thread. */
    void loadModel(Context context, Callback callback);

    /** Transcribe PCM float32 16kHz mono audio. Callback fires on main thread. */
    void transcribe(float[] audio, Callback callback);

    /** Cooperative cancellation of in-progress transcription. */
    void abort();

    /** Check if the model is loaded and ready for transcription. */
    boolean isModelLoaded();

    /**
     * True if transcribed text should be injected straight into the terminal,
     * bypassing the review/validation overlay. Each engine reads the shared
     * direct-send preference; engines that can also display text progressively
     * (whisper.cpp) do so as a side effect, others just deliver the final text.
     */
    boolean isDirectToTerminal();

    /** Release all resources. Must be called when engine is no longer needed. */
    void release();

    /** Get and clear diagnostic logs. May return empty string. */
    String getAndClearLogs();
}
