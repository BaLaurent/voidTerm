package com.voidterm.voice;

import android.content.Context;
import android.content.SharedPreferences;

import com.voidterm.app.SettingsDialog;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * TranscriptionEngine implementation backed by whisper.cpp via JNI.
 * Reads whisper-specific configuration (model name, GPU, beam search, etc.)
 * from SharedPreferences internally.
 *
 * Thread safety: delegates to WhisperBridge which handles its own locking.
 * Config caching uses volatile + listener invalidation (same pattern as
 * the former VoiceInputManager caching).
 */
public class WhisperEngine implements TranscriptionEngine {

    private final WhisperBridge bridge;
    private final SharedPreferences prefs;

    // Cached config — avoids SharedPreferences reads per transcription
    private volatile WhisperConfig cachedConfig;

    private static final Set<String> CONFIG_KEYS = new HashSet<>(Arrays.asList(
            SettingsDialog.KEY_WHISPER_LANGUAGE, SettingsDialog.KEY_WHISPER_TRANSLATE,
            SettingsDialog.KEY_WHISPER_INITIAL_PROMPT, SettingsDialog.KEY_WHISPER_TEMPERATURE,
            SettingsDialog.KEY_WHISPER_BEAM_SEARCH, SettingsDialog.KEY_WHISPER_BEAM_SIZE,
            SettingsDialog.KEY_WHISPER_THREAD_OVERRIDE, SettingsDialog.KEY_WHISPER_SUPPRESS_NON_SPEECH,
            SettingsDialog.KEY_WHISPER_PROPORTIONAL_CONTEXT, SettingsDialog.KEY_VOICE_DIRECT_SEND
    ));

    private final SharedPreferences.OnSharedPreferenceChangeListener configInvalidator =
            (p, key) -> {
                if (CONFIG_KEYS.contains(key)) {
                    cachedConfig = null;
                }
            };

    public WhisperEngine(SharedPreferences prefs) {
        this.prefs = prefs;
        this.bridge = new WhisperBridge();
        prefs.registerOnSharedPreferenceChangeListener(configInvalidator);
        cachedConfig = readConfig(prefs);
    }

    @Override
    public void loadModel(Context context, Callback callback) {
        String modelName = prefs.getString(SettingsDialog.KEY_MODEL_NAME, SettingsDialog.DEFAULT_MODEL);
        boolean useGpu = prefs.getBoolean(SettingsDialog.KEY_USE_GPU, false);
        bridge.loadModel(context, modelName, useGpu, new WhisperBridge.Callback() {
            @Override public void onSuccess(String result) { callback.onSuccess(result); }
            @Override public void onError(String error) { callback.onError(error); }
            @Override public void onProgress(String phase, int percent) { callback.onProgress(phase, percent); }
            @Override public void onPartialResult(String accumulatedText) { callback.onPartialResult(accumulatedText); }
        });
    }

    @Override
    public void transcribe(float[] audio, Callback callback) {
        WhisperConfig config = buildConfig();
        bridge.transcribe(audio, config, new WhisperBridge.Callback() {
            @Override public void onSuccess(String result) { callback.onSuccess(result); }
            @Override public void onError(String error) { callback.onError(error); }
            @Override public void onProgress(String phase, int percent) { callback.onProgress(phase, percent); }
            @Override public void onPartialResult(String accumulatedText) { callback.onPartialResult(accumulatedText); }
        });
    }

    @Override
    public void abort() {
        bridge.abort();
    }

    @Override
    public boolean isModelLoaded() {
        return bridge.isModelLoaded();
    }

    @Override
    public void release() {
        prefs.unregisterOnSharedPreferenceChangeListener(configInvalidator);
        bridge.release();
    }

    @Override
    public String getAndClearLogs() {
        return bridge.getAndClearLogs();
    }

    /** Package-private access to the underlying bridge for DeviceProfiler. */
    WhisperBridge getBridge() {
        return bridge;
    }

    /**
     * Direct-send maps to whisper's streaming flag: when enabled, text bypasses
     * the review overlay AND is displayed progressively (token-by-token) as a
     * side effect of the native segment callback.
     */
    @Override
    public boolean isDirectToTerminal() {
        return buildConfig().streaming;
    }

    /** Get the current model name from preferences. */
    public String getModelName() {
        return prefs.getString(SettingsDialog.KEY_MODEL_NAME, SettingsDialog.DEFAULT_MODEL);
    }

    private WhisperConfig buildConfig() {
        WhisperConfig config = cachedConfig;
        if (config != null) return config;
        config = readConfig(prefs);
        cachedConfig = config;
        return config;
    }

    /** Invalidate cached config (called when prefs change externally). */
    void invalidateConfig() {
        cachedConfig = null;
    }

    private static WhisperConfig readConfig(SharedPreferences prefs) {
        return new WhisperConfig(
                prefs.getString(SettingsDialog.KEY_WHISPER_LANGUAGE, "en"),
                prefs.getBoolean(SettingsDialog.KEY_WHISPER_TRANSLATE, false),
                prefs.getString(SettingsDialog.KEY_WHISPER_INITIAL_PROMPT, ""),
                prefs.getFloat(SettingsDialog.KEY_WHISPER_TEMPERATURE, 0.0f),
                prefs.getBoolean(SettingsDialog.KEY_WHISPER_BEAM_SEARCH, false),
                prefs.getInt(SettingsDialog.KEY_WHISPER_BEAM_SIZE, 5),
                prefs.getInt(SettingsDialog.KEY_WHISPER_THREAD_OVERRIDE, 0),
                prefs.getBoolean(SettingsDialog.KEY_WHISPER_SUPPRESS_NON_SPEECH, false),
                prefs.getBoolean(SettingsDialog.KEY_WHISPER_PROPORTIONAL_CONTEXT, false),
                SettingsDialog.isDirectSendEnabled(prefs)
        );
    }
}
