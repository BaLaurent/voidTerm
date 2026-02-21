package com.voidterm.input;

import android.view.KeyEvent;
import android.util.Log;

import com.voidterm.voice.VoiceInputManager;

/**
 * Maps Meta Quest controller buttons to VoidTerm actions.
 * Intercepts Android KeyEvent and routes Push-to-Talk events
 * to VoiceInputManager.
 */
public class QuestInputHandler {

    private static final String TAG = "QuestInputHandler";
    private static final int DEFAULT_PTT_KEYCODE = KeyEvent.KEYCODE_BUTTON_A;
    private static final long DOUBLE_TAP_THRESHOLD_MS = 300;

    private final VoiceInputManager voiceManager;
    private int pttKeyCode;
    private long lastPttPressTime = 0;
    private boolean pttHeld = false;

    /**
     * @param voiceManager VoiceInputManager to receive PTT events. May be null for graceful degradation.
     */
    public QuestInputHandler(VoiceInputManager voiceManager) {
        this.voiceManager = voiceManager;
        this.pttKeyCode = DEFAULT_PTT_KEYCODE;
    }

    /**
     * Handle key down events. Call this from Activity.onKeyDown().
     *
     * @return true if the event was consumed (PTT key), false to let Android handle it
     */
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (voiceManager == null) {
            return false;
        }

        if (keyCode != pttKeyCode) {
            return false;
        }

        // Ignore repeat events (key held down)
        if (event.getRepeatCount() > 0) {
            return true;
        }

        long now = System.currentTimeMillis();

        // Double-tap detection
        if (now - lastPttPressTime < DOUBLE_TAP_THRESHOLD_MS) {
            Log.d(TAG, "Double-tap detected, cancelling recording");
            voiceManager.onDoubleTap();
            lastPttPressTime = 0; // Reset to prevent triple-tap
            pttHeld = false;
            return true;
        }

        lastPttPressTime = now;
        pttHeld = true;
        voiceManager.onPushToTalkPressed();
        Log.d(TAG, "PTT pressed (keyCode=" + keyCode + ")");
        return true;
    }

    /**
     * Handle key up events. Call this from Activity.onKeyUp().
     *
     * @return true if the event was consumed (PTT key), false to let Android handle it
     */
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (voiceManager == null) {
            return false;
        }

        if (keyCode != pttKeyCode) {
            return false;
        }

        if (pttHeld) {
            pttHeld = false;
            voiceManager.onPushToTalkReleased();
            Log.d(TAG, "PTT released");
        }

        return true;
    }

    /**
     * Set the keycode for Push-to-Talk button.
     * Allows runtime configuration from settings.
     *
     * @param keyCode Android KeyEvent keycode for PTT
     */
    public void setPttKeyCode(int keyCode) {
        this.pttKeyCode = keyCode;
        Log.i(TAG, "PTT keycode set to: " + keyCode);
    }

    /**
     * Get current PTT keycode.
     */
    public int getPttKeyCode() {
        return pttKeyCode;
    }
}
