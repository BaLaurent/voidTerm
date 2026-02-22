package com.voidterm.contracts;

/**
 * Voice input state machine states.
 * Transitions: Loading→Idle→Recording→Transcribing→ShowingResult→Editing→Error→Idle
 */
public enum VoiceState {
    LOADING,
    IDLE,
    RECORDING,
    TRANSCRIBING,
    SHOWING_RESULT,
    EDITING,
    ERROR
}
