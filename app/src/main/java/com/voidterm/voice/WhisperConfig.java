package com.voidterm.voice;

/**
 * Immutable configuration for whisper.cpp transcription parameters.
 * Built from SharedPreferences at each transcription call so changes apply immediately.
 */
public class WhisperConfig {
    public final String language;
    public final boolean translate;
    public final String initialPrompt;
    public final float temperature;
    public final boolean useBeamSearch;
    public final int beamSize;
    public final int threadCount;
    public final boolean suppressNonSpeech;

    public WhisperConfig(String language, boolean translate, String initialPrompt,
                         float temperature, boolean useBeamSearch, int beamSize,
                         int threadCount, boolean suppressNonSpeech) {
        this.language = language;
        this.translate = translate;
        this.initialPrompt = initialPrompt;
        this.temperature = temperature;
        this.useBeamSearch = useBeamSearch;
        this.beamSize = beamSize;
        this.threadCount = threadCount;
        this.suppressNonSpeech = suppressNonSpeech;
    }
}
