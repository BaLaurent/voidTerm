package com.voidterm.contracts;

/**
 * Callback for voice system to inject text into terminal PTY.
 * Implemented by TermuxTerminalSessionClient (Phase 4).
 * Called by VoiceInputManager when user confirms transcription.
 */
public interface VoiceInputCallback {
    /**
     * Called when user confirms transcription (Send button or Enter key).
     * Text should be injected into terminal PTY WITHOUT appending newline.
     */
    void onVoiceTextReady(String text);

    /**
     * Called on every voice state transition for UI/logging purposes.
     */
    void onVoiceStateChanged(VoiceState newState);
}
