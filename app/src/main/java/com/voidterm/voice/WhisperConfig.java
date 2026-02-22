package com.voidterm.voice;

/**
 * Immutable configuration for whisper.cpp transcription parameters.
 * Cached in VoiceInputManager and invalidated on SharedPreferences change.
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
    public final boolean useProportionalContext;
    public final boolean streaming;

    public WhisperConfig(String language, boolean translate, String initialPrompt,
                         float temperature, boolean useBeamSearch, int beamSize,
                         int threadCount, boolean suppressNonSpeech,
                         boolean useProportionalContext, boolean streaming) {
        this.language = language;
        this.translate = translate;
        this.initialPrompt = initialPrompt;
        this.temperature = temperature;
        this.useBeamSearch = useBeamSearch;
        this.beamSize = beamSize;
        this.threadCount = threadCount;
        this.suppressNonSpeech = suppressNonSpeech;
        this.useProportionalContext = useProportionalContext;
        this.streaming = streaming;
    }
}
