package com.voidterm.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Settings constants for VoidTerm. All SharedPreferences keys, label/value
 * arrays, and static helpers live here. The actual UI is in SettingsActivity.
 */
public final class SettingsDialog {

    public static final String PREFS_NAME = "voidterm_settings";
    public static final String KEY_MODEL_NAME = "whisper_model_name";
    public static final String KEY_COMPACT_TOOLBAR = "compact_toolbar_enabled";
    public static final String KEY_TAP_TOGGLE_KEYBOARD = "tap_toggle_keyboard";
    public static final String KEY_HAPTIC_FEEDBACK = "haptic_feedback";
    public static final String KEY_FULLSCREEN_MODE = "fullscreen_mode";
    public static final String KEY_PANEL_MODE = "panel_mode";
    public static final String PANEL_GAMEBOY = "gameboy";
    public static final String PANEL_COMPACT = "compact";
    public static final String PANEL_FULLSCREEN = "fullscreen";
    public static final String KEY_USE_GPU = "use_gpu";
    public static final String KEY_BACK_BEHAVIOR = "back_key_behavior";
    public static final String KEY_BACK_MACRO = "back_key_macro";
    public static final String KEY_WHISPER_LANGUAGE = "whisper_language";
    public static final String KEY_WHISPER_TRANSLATE = "whisper_translate";
    public static final String KEY_WHISPER_INITIAL_PROMPT = "whisper_initial_prompt";
    public static final String KEY_WHISPER_TEMPERATURE = "whisper_temperature";
    public static final String KEY_WHISPER_BEAM_SEARCH = "whisper_beam_search";
    public static final String KEY_WHISPER_BEAM_SIZE = "whisper_beam_size";
    public static final String KEY_WHISPER_THREAD_OVERRIDE = "whisper_thread_override";
    public static final String KEY_WHISPER_SUPPRESS_NON_SPEECH = "whisper_suppress_non_speech";
    public static final String KEY_WHISPER_PROPORTIONAL_CONTEXT = "whisper_proportional_context";
    public static final String KEY_WHISPER_STREAMING = "whisper_streaming"; // legacy, migrated to KEY_VOICE_DIRECT_SEND
    // Engine-agnostic delivery: send transcription straight to the terminal,
    // bypassing the review/validation overlay. Replaces the old whisper-only
    // "streaming" toggle (which conflated direct delivery with progressive display).
    public static final String KEY_VOICE_DIRECT_SEND = "voice_direct_send";
    // When direct-send is on, also press Enter (\r) after the final text so the
    // command executes itself (hands-free in VR). Default off.
    public static final String KEY_VOICE_AUTO_SUBMIT = "voice_auto_submit";
    public static final String KEY_AUDIO_PREPROCESSING = "audio_preprocessing";
    public static final String KEY_AUDIO_GAIN = "audio_gain";
    public static final String KEY_AUDIO_PRE_EMPHASIS = "audio_pre_emphasis";
    public static final String KEY_AUDIO_HP_CUTOFF = "audio_hp_cutoff";
    public static final String KEY_AUDIO_NORM_TARGET = "audio_norm_target";
    public static final String KEY_THEME = "interface_theme";
    public static final String BACK_ESCAPE = "escape";
    public static final String BACK_TOGGLE_KEYBOARD = "toggle_keyboard";
    public static final String BACK_MACRO = "macro";
    public static final String BACK_VOICE = "voice_input";
    public static final String KEY_VOLUME_UP_BEHAVIOR = "volume_up_behavior";
    public static final String KEY_VOLUME_DOWN_BEHAVIOR = "volume_down_behavior";
    public static final String KEY_VOLUME_UP_MACRO = "volume_up_macro";
    public static final String KEY_VOLUME_DOWN_MACRO = "volume_down_macro";
    public static final String VOLUME_DEFAULT = "default";

    // --- Key gesture slots (multi-tap / long / combo) ---
    // Single-tap reuses the existing keys above; "default" sentinel reuses VOLUME_DEFAULT.
    public static final String KEY_GESTURE_TIMING_PRESET = "gesture_timing_preset";
    public static final String PRESET_FAST = "fast";
    public static final String PRESET_NORMAL = "normal";
    public static final String PRESET_SLOW = "slow";

    public static final String KEY_VOLUP_DOUBLE = "gesture_volup_double";
    public static final String KEY_VOLUP_TRIPLE = "gesture_volup_triple";
    public static final String KEY_VOLUP_LONG = "gesture_volup_long";
    public static final String KEY_VOLUP_DOUBLE_MACRO = "gesture_volup_double_macro";
    public static final String KEY_VOLUP_TRIPLE_MACRO = "gesture_volup_triple_macro";
    public static final String KEY_VOLUP_LONG_MACRO = "gesture_volup_long_macro";

    public static final String KEY_VOLDOWN_DOUBLE = "gesture_voldown_double";
    public static final String KEY_VOLDOWN_TRIPLE = "gesture_voldown_triple";
    public static final String KEY_VOLDOWN_LONG = "gesture_voldown_long";
    public static final String KEY_VOLDOWN_DOUBLE_MACRO = "gesture_voldown_double_macro";
    public static final String KEY_VOLDOWN_TRIPLE_MACRO = "gesture_voldown_triple_macro";
    public static final String KEY_VOLDOWN_LONG_MACRO = "gesture_voldown_long_macro";

    public static final String KEY_BACK_DOUBLE = "gesture_back_double";
    public static final String KEY_BACK_TRIPLE = "gesture_back_triple";
    public static final String KEY_BACK_LONG = "gesture_back_long";
    public static final String KEY_BACK_DOUBLE_MACRO = "gesture_back_double_macro";
    public static final String KEY_BACK_TRIPLE_MACRO = "gesture_back_triple_macro";
    public static final String KEY_BACK_LONG_MACRO = "gesture_back_long_macro";

    public static final String KEY_COMBO_SINGLE = "gesture_combo_single";
    public static final String KEY_COMBO_DOUBLE = "gesture_combo_double";
    public static final String KEY_COMBO_TRIPLE = "gesture_combo_triple";
    public static final String KEY_COMBO_SINGLE_MACRO = "gesture_combo_single_macro";
    public static final String KEY_COMBO_DOUBLE_MACRO = "gesture_combo_double_macro";
    public static final String KEY_COMBO_TRIPLE_MACRO = "gesture_combo_triple_macro";

    public static final String[] GESTURE_PRESET_LABELS = {"Reactif", "Normal", "Tolerant"};
    public static final String[] GESTURE_PRESET_VALUES = {PRESET_FAST, PRESET_NORMAL, PRESET_SLOW};

    public static final String DEFAULT_MODEL = "ggml-base.bin";
    public static final int REQUEST_MODEL_FILE = 1001;
    public static final String KEY_MODEL_RELOAD_REQUESTED = "model_reload_requested";

    // Transcription engine selection
    public static final String KEY_TRANSCRIPTION_ENGINE = "transcription_engine";
    public static final String ENGINE_WHISPER = "whisper";
    public static final String ENGINE_PARAKEET = "parakeet";
    public static final String[] ENGINE_LABELS = {"Whisper (whisper.cpp)", "Parakeet TDT v3 (ONNX)"};
    public static final String[] ENGINE_VALUES = {ENGINE_WHISPER, ENGINE_PARAKEET};
    // Single source of truth for the default engine. Parakeet is preferred — better
    // accuracy than whisper.cpp on Quest, and neither engine ships a bundled model
    // (assets/models/ is empty), so both require the user to provide one regardless.
    public static final String ENGINE_DEFAULT = ENGINE_PARAKEET;

    // Parakeet (ONNX) engine tunables. Defaults live in ParakeetConfig.DEFAULT.
    public static final String KEY_PARAKEET_THREAD_OVERRIDE = "parakeet_thread_override";
    public static final String KEY_PARAKEET_MAX_WINDOW_SEC = "parakeet_max_window_sec";
    public static final String KEY_PARAKEET_OVERLAP_SEC = "parakeet_overlap_sec";
    public static final String KEY_PARAKEET_SILENCE_THRESHOLD = "parakeet_silence_threshold";
    public static final String KEY_PARAKEET_MAX_TOKENS_STEP = "parakeet_max_tokens_step";
    // Parakeet quantization selection (int8 vs fp32). NOT a CONFIG_KEY — changing it
    // requires a full engine reload (different ONNX files), via KEY_MODEL_RELOAD_REQUESTED.
    public static final String KEY_PARAKEET_QUANTIZATION = "parakeet_quantization";
    public static final String PARAKEET_QUANT_DEFAULT = "int8";

    public static final String[] LANGUAGE_LABELS = {
        "Auto-detect", "English", "Chinese", "Spanish", "Hindi", "Arabic",
        "French", "Bengali", "Portuguese", "Russian", "Japanese", "German",
        "Korean", "Indonesian", "Turkish", "Vietnamese", "Italian", "Thai",
        "Polish", "Dutch", "Ukrainian", "Romanian", "Greek", "Czech",
        "Swedish", "Malay"
    };
    public static final String[] LANGUAGE_CODES = {
        "auto", "en", "zh", "es", "hi", "ar",
        "fr", "bn", "pt", "ru", "ja", "de",
        "ko", "id", "tr", "vi", "it", "th",
        "pl", "nl", "uk", "ro", "el", "cs",
        "sv", "ms"
    };
    public static final String[] TEMPERATURE_LABELS = {
        "0.0 (Precise)", "0.2", "0.4", "0.6", "0.8", "1.0 (Creative)"
    };
    public static final float[] TEMPERATURE_VALUES = {
        0.0f, 0.2f, 0.4f, 0.6f, 0.8f, 1.0f
    };
    public static final String[] GAIN_LABELS = {
        "0.5x", "1.0x (Default)", "1.5x", "2.0x", "3.0x"
    };
    public static final float[] GAIN_VALUES = {0.5f, 1.0f, 1.5f, 2.0f, 3.0f};
    public static final String[] PRE_EMPHASIS_LABELS = {
        "Off", "0.90 (Light)", "0.95", "0.97 (Default)", "0.99 (Heavy)"
    };
    public static final float[] PRE_EMPHASIS_VALUES = {0.0f, 0.90f, 0.95f, 0.97f, 0.99f};
    public static final String[] HP_CUTOFF_LABELS = {
        "40 Hz", "60 Hz", "80 Hz (Default)", "100 Hz", "120 Hz"
    };
    public static final int[] HP_CUTOFF_VALUES = {40, 60, 80, 100, 120};
    public static final String[] NORM_TARGET_LABELS = {
        "0.5 (Quiet)", "0.7", "0.9 (Default)", "1.0 (Max)"
    };
    public static final float[] NORM_TARGET_VALUES = {0.5f, 0.7f, 0.9f, 1.0f};

    private SettingsDialog() {}

    static boolean isHapticEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_HAPTIC_FEEDBACK, true);
    }

    /**
     * Migrate old fullscreen_mode boolean to panel_mode string.
     * Returns the current panel mode value after migration.
     */
    public static String migratePanelMode(SharedPreferences prefs) {
        if (prefs.contains(KEY_PANEL_MODE)) {
            return prefs.getString(KEY_PANEL_MODE, PANEL_GAMEBOY);
        }
        // Migrate from old fullscreen_mode boolean
        if (prefs.contains(KEY_FULLSCREEN_MODE)) {
            boolean wasFullscreen = prefs.getBoolean(KEY_FULLSCREEN_MODE, false);
            String mode = wasFullscreen ? PANEL_FULLSCREEN : PANEL_GAMEBOY;
            prefs.edit()
                    .putString(KEY_PANEL_MODE, mode)
                    .remove(KEY_FULLSCREEN_MODE)
                    .apply();
            return mode;
        }
        return PANEL_GAMEBOY;
    }

    /**
     * Read the direct-send preference, migrating from the legacy whisper-only
     * "whisper_streaming" key on first access. Migration is idempotent: once the
     * new key exists, the old one is gone and never consulted again.
     */
    public static boolean isDirectSendEnabled(SharedPreferences prefs) {
        if (prefs.contains(KEY_VOICE_DIRECT_SEND)) {
            return prefs.getBoolean(KEY_VOICE_DIRECT_SEND, false);
        }
        if (prefs.contains(KEY_WHISPER_STREAMING)) {
            boolean legacy = prefs.getBoolean(KEY_WHISPER_STREAMING, false);
            prefs.edit()
                    .putBoolean(KEY_VOICE_DIRECT_SEND, legacy)
                    .remove(KEY_WHISPER_STREAMING)
                    .apply();
            return legacy;
        }
        return false;
    }
}
