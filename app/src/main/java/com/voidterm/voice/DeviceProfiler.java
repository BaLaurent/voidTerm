package com.voidterm.voice;

import android.content.SharedPreferences;
import android.util.Log;

import com.voidterm.app.SettingsDialog;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Auto-tunes whisper.cpp transcription parameters based on device performance.
 *
 * Runs a micro-benchmark (1s synthetic audio, greedy decode) after model load,
 * classifies the device into a performance tier, and writes optimal defaults
 * for beam search, proportional context, and suppress non-speech.
 *
 * User overrides are tracked: if the user manually changes an auto-tunable
 * parameter, it is never overwritten by auto-tuning again (until "Reset to Auto").
 *
 * Benchmark results are cached per model name — re-profiling only occurs on
 * model change or explicit reset.
 */
public class DeviceProfiler {

    private static final String TAG = "DeviceProfiler";

    // SharedPreferences keys for autotune state
    public static final String KEY_AUTOTUNE_MODEL = "autotune_model";
    public static final String KEY_AUTOTUNE_BENCHMARK_MS = "autotune_benchmark_ms";
    public static final String KEY_AUTOTUNE_TIER = "autotune_tier";
    public static final String KEY_USER_OVERRIDES = "user_overrides";
    static final String KEY_AUTOTUNE_MIGRATED = "autotune_migrated";

    // Auto-tunable parameter keys
    private static final Set<String> AUTOTUNE_KEYS = new HashSet<>(Arrays.asList(
            SettingsDialog.KEY_WHISPER_BEAM_SEARCH,
            SettingsDialog.KEY_WHISPER_BEAM_SIZE,
            SettingsDialog.KEY_WHISPER_SUPPRESS_NON_SPEECH,
            SettingsDialog.KEY_WHISPER_PROPORTIONAL_CONTEXT
    ));

    // Old hardcoded defaults (before auto-tuning existed) — used for migration
    private static final boolean OLD_DEFAULT_BEAM_SEARCH = false;
    private static final int OLD_DEFAULT_BEAM_SIZE = 5;
    private static final boolean OLD_DEFAULT_SUPPRESS_NON_SPEECH = false;
    private static final boolean OLD_DEFAULT_PROPORTIONAL_CONTEXT = false;

    // Benchmark thresholds (ms for 1s audio transcription)
    private static final long FAST_THRESHOLD_MS = 600;
    private static final long MEDIUM_THRESHOLD_MS = 1200;

    public enum Tier {
        FAST, MEDIUM, SLOW
    }

    public static class Result {
        public final Tier tier;
        public final long benchmarkMs;

        Result(Tier tier, long benchmarkMs) {
            this.tier = tier;
            this.benchmarkMs = benchmarkMs;
        }
    }

    private DeviceProfiler() {}

    /**
     * Generate 1 second of 440Hz sine wave at 16kHz sample rate.
     * Activates full whisper encoder+decoder pipeline (silence may be short-circuited).
     */
    static float[] generateBenchmarkAudio() {
        int sampleRate = 16000;
        float[] audio = new float[sampleRate];
        double frequency = 440.0;
        for (int i = 0; i < audio.length; i++) {
            audio[i] = (float) Math.sin(2.0 * Math.PI * frequency * i / sampleRate);
        }
        return audio;
    }

    /**
     * Run a micro-benchmark: transcribe 1s of synthetic audio with greedy decoding.
     * Must be called on a background thread. Returns null on error.
     */
    static Result profile(WhisperBridge bridge, int threadCount) {
        float[] audio = generateBenchmarkAudio();

        Log.i(TAG, "Starting benchmark with " + threadCount + " threads...");
        long elapsed = bridge.benchmarkTranscribe(audio, threadCount);

        if (elapsed < 0) {
            Log.e(TAG, "Benchmark failed");
            return null;
        }

        Tier tier;
        if (elapsed < FAST_THRESHOLD_MS) {
            tier = Tier.FAST;
        } else if (elapsed < MEDIUM_THRESHOLD_MS) {
            tier = Tier.MEDIUM;
        } else {
            tier = Tier.SLOW;
        }

        Log.i(TAG, "Benchmark: " + elapsed + "ms -> " + tier);
        return new Result(tier, elapsed);
    }

    /**
     * Check if profiling is needed for the current model.
     */
    static boolean needsProfiling(SharedPreferences prefs, String modelName) {
        String cachedModel = prefs.getString(KEY_AUTOTUNE_MODEL, null);
        return !modelName.equals(cachedModel);
    }

    /**
     * Apply optimal defaults for the given tier, respecting user overrides.
     * Saves benchmark metadata and writes auto-tunable parameters only if
     * the user has not manually overridden them.
     */
    static void applyDefaults(SharedPreferences prefs, String modelName, Result result) {
        Set<String> overrides = prefs.getStringSet(KEY_USER_OVERRIDES, new HashSet<>());
        SharedPreferences.Editor editor = prefs.edit();

        // Save benchmark metadata
        editor.putString(KEY_AUTOTUNE_MODEL, modelName);
        editor.putLong(KEY_AUTOTUNE_BENCHMARK_MS, result.benchmarkMs);
        editor.putString(KEY_AUTOTUNE_TIER, result.tier.name());

        // Apply tier-specific defaults only for non-overridden keys
        if (!overrides.contains(SettingsDialog.KEY_WHISPER_BEAM_SEARCH)) {
            editor.putBoolean(SettingsDialog.KEY_WHISPER_BEAM_SEARCH, result.tier != Tier.SLOW);
        }
        if (!overrides.contains(SettingsDialog.KEY_WHISPER_BEAM_SIZE)) {
            editor.putInt(SettingsDialog.KEY_WHISPER_BEAM_SIZE, result.tier == Tier.FAST ? 5 : 3);
        }
        if (!overrides.contains(SettingsDialog.KEY_WHISPER_PROPORTIONAL_CONTEXT)) {
            editor.putBoolean(SettingsDialog.KEY_WHISPER_PROPORTIONAL_CONTEXT, true);
        }
        if (!overrides.contains(SettingsDialog.KEY_WHISPER_SUPPRESS_NON_SPEECH)) {
            editor.putBoolean(SettingsDialog.KEY_WHISPER_SUPPRESS_NON_SPEECH, true);
        }

        editor.apply();
        Log.i(TAG, "Applied " + result.tier + " defaults (overrides: " + overrides + ")");
    }

    /**
     * One-time migration: detect parameters already modified before auto-tuning existed.
     * Marks them as user overrides so auto-tuning doesn't overwrite user choices.
     */
    static void migrateIfNeeded(SharedPreferences prefs) {
        if (prefs.getBoolean(KEY_AUTOTUNE_MIGRATED, false)) {
            return;
        }

        Set<String> overrides = new HashSet<>(prefs.getStringSet(KEY_USER_OVERRIDES, new HashSet<>()));

        if (prefs.contains(SettingsDialog.KEY_WHISPER_BEAM_SEARCH)
                && prefs.getBoolean(SettingsDialog.KEY_WHISPER_BEAM_SEARCH, OLD_DEFAULT_BEAM_SEARCH) != OLD_DEFAULT_BEAM_SEARCH) {
            overrides.add(SettingsDialog.KEY_WHISPER_BEAM_SEARCH);
        }
        if (prefs.contains(SettingsDialog.KEY_WHISPER_BEAM_SIZE)
                && prefs.getInt(SettingsDialog.KEY_WHISPER_BEAM_SIZE, OLD_DEFAULT_BEAM_SIZE) != OLD_DEFAULT_BEAM_SIZE) {
            overrides.add(SettingsDialog.KEY_WHISPER_BEAM_SIZE);
        }
        if (prefs.contains(SettingsDialog.KEY_WHISPER_SUPPRESS_NON_SPEECH)
                && prefs.getBoolean(SettingsDialog.KEY_WHISPER_SUPPRESS_NON_SPEECH, OLD_DEFAULT_SUPPRESS_NON_SPEECH) != OLD_DEFAULT_SUPPRESS_NON_SPEECH) {
            overrides.add(SettingsDialog.KEY_WHISPER_SUPPRESS_NON_SPEECH);
        }
        if (prefs.contains(SettingsDialog.KEY_WHISPER_PROPORTIONAL_CONTEXT)
                && prefs.getBoolean(SettingsDialog.KEY_WHISPER_PROPORTIONAL_CONTEXT, OLD_DEFAULT_PROPORTIONAL_CONTEXT) != OLD_DEFAULT_PROPORTIONAL_CONTEXT) {
            overrides.add(SettingsDialog.KEY_WHISPER_PROPORTIONAL_CONTEXT);
        }

        SharedPreferences.Editor editor = prefs.edit();
        if (!overrides.isEmpty()) {
            editor.putStringSet(KEY_USER_OVERRIDES, overrides);
            Log.i(TAG, "Migration: detected user overrides: " + overrides);
        }
        editor.putBoolean(KEY_AUTOTUNE_MIGRATED, true);
        editor.apply();
    }

    /**
     * Mark a parameter key as user-overridden.
     * Called by SettingsDialog when the user manually changes an auto-tunable parameter.
     */
    public static void markUserOverride(SharedPreferences prefs, String key) {
        if (!AUTOTUNE_KEYS.contains(key)) return;
        Set<String> overrides = new HashSet<>(prefs.getStringSet(KEY_USER_OVERRIDES, new HashSet<>()));
        if (overrides.add(key)) {
            prefs.edit().putStringSet(KEY_USER_OVERRIDES, overrides).apply();
        }
    }

    /**
     * Clear all user overrides and force re-profiling on next model load.
     */
    public static void resetToAuto(SharedPreferences prefs) {
        prefs.edit()
                .remove(KEY_USER_OVERRIDES)
                .remove(KEY_AUTOTUNE_MODEL)
                .apply();
        Log.i(TAG, "Reset to auto — will re-profile on next model load");
    }
}
