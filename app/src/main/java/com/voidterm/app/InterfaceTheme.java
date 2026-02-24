package com.voidterm.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/**
 * Interface color themes for GameBoyControlPanel, CompactToolbar, and session drawer.
 * Each theme defines 7 panel colors + 2 drawer colors.
 */
public enum InterfaceTheme {

    GAMEBOY(
        "GameBoy",
        0xFFC4C4B4, // background
        0xFF2B2B2B, // dpad
        0xFF1A1A1A, // cross
        0xFF9B2257, // primary
        0xFF3C3C6E, // modifier
        0xFF585858, // macro
        0xFF9BBC0F, // active
        0xFFC4C4B4, // drawerBg — cream, matches panel background
        0xFF9B2257  // drawerAccent — wine/magenta, matches A/B buttons
    ),
    DARK_GAMEBOY(
        "Dark GameBoy",
        0xFF1A1A1A,
        0xFF3A3A3A,
        0xFF252525,
        0xFF6B1840,
        0xFF2A2A55,
        0xFF404040,
        0xFF6B8A0A,
        0xFF2A2A55, // drawerBg — dark indigo, matches modifier
        0xFF9BBC0F  // drawerAccent — bright lime (not the muted olive)
    ),
    ATOMIC_PURPLE(
        "Atomic Purple",
        0xFF9888B8, // background — translucent purple shell
        0xFF2D2838, // dpad — dark purple-charcoal
        0xFF1E1A28, // cross — deep purple cavity
        0xFF7B3FA0, // primary — rich violet
        0xFF3A2D5C, // modifier — dark indigo
        0xFF504060, // macro — muted purple-gray
        0xFFBB66FF, // active — vivid purple glow
        0xFF1E1A28, // drawerBg — deep purple cavity
        0xFFBB66FF  // drawerAccent — vivid purple glow
    ),
    HACKERBOY(
        "HackerBoy",
        0xFF0A0A0A,
        0xFF1A1A1A,
        0xFF0D0D0D,
        0xFF00CC66,
        0xFF006633,
        0xFF1A331A,
        0xFF00FF41,
        0xFF0D0D0D, // drawerBg — near-black
        0xFF00FF41  // drawerAccent — matrix green
    );

    public final String label;
    public final int background;
    public final int dpad;
    public final int cross;
    public final int primary;
    public final int modifier;
    public final int macro;
    public final int active;
    public final int drawerBg;
    public final int drawerAccent;

    InterfaceTheme(String label, int background, int dpad, int cross,
                   int primary, int modifier, int macro, int active,
                   int drawerBg, int drawerAccent) {
        this.label = label;
        this.background = background;
        this.dpad = dpad;
        this.cross = cross;
        this.primary = primary;
        this.modifier = modifier;
        this.macro = macro;
        this.active = active;
        this.drawerBg = drawerBg;
        this.drawerAccent = drawerAccent;
    }

    public static InterfaceTheme current(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE);
        String name = prefs.getString(SettingsDialog.KEY_THEME, GAMEBOY.name());
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return GAMEBOY;
        }
    }

    public static void save(Context context, InterfaceTheme theme) {
        context.getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(SettingsDialog.KEY_THEME, theme.name()).apply();
    }

    public static int darkenColor(int color, float factor) {
        int r = Math.max(0, (int) (Color.red(color) * factor));
        int g = Math.max(0, (int) (Color.green(color) * factor));
        int b = Math.max(0, (int) (Color.blue(color) * factor));
        return Color.argb(Color.alpha(color), r, g, b);
    }

    public static int lightenColor(int color, float factor) {
        int r = Math.min(255, (int) (Color.red(color) * factor));
        int g = Math.min(255, (int) (Color.green(color) * factor));
        int b = Math.min(255, (int) (Color.blue(color) * factor));
        return Color.argb(Color.alpha(color), r, g, b);
    }

    /** True if the color is perceptually light (needs dark text on top). */
    public static boolean isLightColor(int color) {
        double brightness = 0.299 * Color.red(color)
                + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color);
        return brightness > 128;
    }
}
