package com.voidterm.contracts;

/**
 * Listener for user actions on the TranscriptionOverlay.
 * Fired by TranscriptionOverlay, consumed by VoiceInputManager.
 */
public interface TranscriptionListener {
    /**
     * User tapped Send button or pressed Enter.
     * @param text The transcription text (possibly edited by user)
     */
    void onSendRequested(String text);

    /**
     * User tapped Cancel button or pressed Escape.
     */
    void onCancelRequested();

    /**
     * User started modifying the transcription text.
     */
    void onEditStarted();
}
