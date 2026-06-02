package com.voidterm.input;

/**
 * Immutable gesture timing. Built from a 3-level preset so users never type
 * raw milliseconds. Follows the SharedPreferences -> immutable config pattern
 * used by WhisperConfig / AudioConfig.
 */
public final class GestureTiming {
    public final long multiTapWindowMs;
    public final long longPressMs;
    public final long comboWindowMs;

    public GestureTiming(long multiTapWindowMs, long longPressMs, long comboWindowMs) {
        this.multiTapWindowMs = multiTapWindowMs;
        this.longPressMs = longPressMs;
        this.comboWindowMs = comboWindowMs;
    }

    public static final GestureTiming FAST = new GestureTiming(200, 400, 120);
    public static final GestureTiming NORMAL = new GestureTiming(280, 500, 180);
    public static final GestureTiming SLOW = new GestureTiming(400, 700, 280);

    public static GestureTiming fromPreset(String preset) {
        if ("fast".equals(preset)) return FAST;
        if ("slow".equals(preset)) return SLOW;
        return NORMAL;
    }
}
