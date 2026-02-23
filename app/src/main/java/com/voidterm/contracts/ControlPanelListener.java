package com.voidterm.contracts;

/**
 * Communication contract between control panels (GameBoy, Compact, CompactToolbar)
 * and the Activity. All methods are called on the main thread.
 */
public interface ControlPanelListener {
    /** Send text or escape sequence to the terminal PTY. */
    void onSendToTerminal(String text);
    /** Toggle voice input recording (Push-to-Talk). */
    void onVoiceToggle();
    /** Open the settings dialog. */
    void onSettingsRequested();
}
