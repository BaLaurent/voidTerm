package com.voidterm.app;

import android.util.Log;

import com.voidterm.contracts.VoiceState;
import com.voidterm.voice.VoiceInputManager;

/**
 * Configuration and handler for the extra keys row.
 * Adds a microphone button for Push-to-Talk and increases
 * button sizes for Quest controller raycast compatibility.
 *
 * In the actual Termux fork, this modifies the existing extra keys
 * configuration to add the mic button and resize buttons.
 *
 * Default extra keys layout with mic button:
 * [ESC] [TAB] [CTRL] [ALT] [mic] [UP] [DOWN] [LEFT] [RIGHT]
 *
 * Button height: 60dp (150% of Termux standard 40dp)
 * per quest_defaults.xml quest_extra_key_height
 */
public class ExtraKeysConfig {

    private static final String TAG = "ExtraKeysConfig";

    /**
     * Default extra keys layout string with mic button.
     * In real Termux fork, this is the JSON config for ExtraKeysView.
     */
    public static final String DEFAULT_EXTRA_KEYS_CONFIG =
            "[[\"ESC\",\"TAB\",\"CTRL\",\"ALT\",\"\uD83C\uDFA4\","
            + "{\"key\":\"UP\",\"popup\":\"PGUP\"},"
            + "{\"key\":\"DOWN\",\"popup\":\"PGDN\"},"
            + "{\"key\":\"LEFT\",\"popup\":\"HOME\"},"
            + "{\"key\":\"RIGHT\",\"popup\":\"END\"}]]";

    /**
     * Button height in dp (150% of Termux standard 40dp).
     * Read from quest_defaults.xml quest_extra_key_height = 60dp.
     */
    public static final int BUTTON_HEIGHT_DP = 60;

    /**
     * Mic button special key identifier.
     */
    public static final String MIC_BUTTON_KEY = "\uD83C\uDFA4";

    private final VoiceInputManager voiceInputManager;

    public ExtraKeysConfig(VoiceInputManager voiceInputManager) {
        this.voiceInputManager = voiceInputManager;
    }

    /**
     * Handle extra key button press.
     * Special handling for mic button: toggle Push-to-Talk.
     * Recording state is derived from VoiceInputManager — no duplicate tracking.
     *
     * @param key The key label that was pressed
     * @return true if the key was handled (mic button), false for normal keys
     */
    public boolean onExtraKeyPressed(String key) {
        if (!MIC_BUTTON_KEY.equals(key)) {
            return false;
        }

        if (voiceInputManager == null) {
            Log.w(TAG, "VoiceInputManager not available");
            return true;
        }

        VoiceState state = voiceInputManager.getCurrentState();

        if (state == VoiceState.IDLE) {
            voiceInputManager.onPushToTalkPressed();
            Log.d(TAG, "Mic button: start recording");
        } else if (state == VoiceState.RECORDING) {
            voiceInputManager.onPushToTalkReleased();
            Log.d(TAG, "Mic button: stop recording");
        }

        return true;
    }

    /**
     * Check if mic button should show recording indicator.
     *
     * @return true if currently recording (button should show red tint)
     */
    public boolean isMicRecording() {
        return voiceInputManager != null
                && voiceInputManager.getCurrentState() == VoiceState.RECORDING;
    }
}
