package com.voidterm.contracts;

/**
 * Voice input state machine states.
 * Transitions: Idle→Recording→Transcribing→ShowingResult→Editing→Error→Idle
 */
public enum VoiceState {
    IDLE,
    RECORDING,
    TRANSCRIBING,
    SHOWING_RESULT,
    EDITING,
    ERROR
}
