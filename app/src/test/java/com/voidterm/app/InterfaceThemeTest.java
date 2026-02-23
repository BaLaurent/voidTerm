package com.voidterm.app;

import android.content.Context;
import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link InterfaceTheme} enum: color utilities, enum values,
 * and SharedPreferences-based current/save persistence.
 */
@RunWith(RobolectricTestRunner.class)
public class InterfaceThemeTest {

    // ---------------------------------------------------------------
    // darkenColor() tests
    // ---------------------------------------------------------------

    @Test
    public void darkenColor_halfOnWhite_halveChannels() {
        int result = InterfaceTheme.darkenColor(0xFFFFFFFF, 0.5f);
        assertEquals(255, Color.alpha(result));
        assertEquals(127, Color.red(result));
        assertEquals(127, Color.green(result));
        assertEquals(127, Color.blue(result));
    }

    @Test
    public void darkenColor_preservesAlpha() {
        int result = InterfaceTheme.darkenColor(0x80FF0000, 0.5f);
        assertEquals(0x80, Color.alpha(result));
        assertEquals(127, Color.red(result));
        assertEquals(0, Color.green(result));
        assertEquals(0, Color.blue(result));
    }

    @Test
    public void darkenColor_factorZero_allChannelsZeroAlphaPreserved() {
        int result = InterfaceTheme.darkenColor(0xFFABCDEF, 0.0f);
        assertEquals(255, Color.alpha(result));
        assertEquals(0, Color.red(result));
        assertEquals(0, Color.green(result));
        assertEquals(0, Color.blue(result));
    }

    @Test
    public void darkenColor_factorAboveOne_doesNotClamp() {
        // darkenColor does NOT clamp to 255 (unlike lightenColor).
        // 0xFF646464: R=100, G=100, B=100. Factor 1.5 -> (int)(100*1.5) = 150.
        int result = InterfaceTheme.darkenColor(0xFF646464, 1.5f);
        assertEquals(255, Color.alpha(result));
        // (int)(100 * 1.5f) = 150 — fits in byte, no overflow
        assertEquals(150, Color.red(result));
        assertEquals(150, Color.green(result));
        assertEquals(150, Color.blue(result));
    }

    // ---------------------------------------------------------------
    // lightenColor() tests
    // ---------------------------------------------------------------

    @Test
    public void lightenColor_doubleOnMidGray_clampsTo255() {
        // 0xFF808080: R=128, G=128, B=128. Factor 2.0 -> (int)(128*2.0) = 256, clamped to 255.
        int result = InterfaceTheme.lightenColor(0xFF808080, 2.0f);
        assertEquals(255, Color.alpha(result));
        assertEquals(255, Color.red(result));
        assertEquals(255, Color.green(result));
        assertEquals(255, Color.blue(result));
    }

    @Test
    public void lightenColor_clampsIndividualChannels() {
        // 0xFF00FF00: R=0, G=255, B=0. Factor 1.5 -> G = min(255, 382) = 255, R = 0, B = 0.
        int result = InterfaceTheme.lightenColor(0xFF00FF00, 1.5f);
        assertEquals(255, Color.alpha(result));
        assertEquals(0, Color.red(result));
        assertEquals(255, Color.green(result));
        assertEquals(0, Color.blue(result));
    }

    @Test
    public void lightenColor_factorOne_unchanged() {
        int color = 0xFF123456;
        int result = InterfaceTheme.lightenColor(color, 1.0f);
        assertEquals(color, result);
    }

    // ---------------------------------------------------------------
    // Enum structure tests
    // ---------------------------------------------------------------

    @Test
    public void enumHasExactlyFourValues() {
        assertEquals(4, InterfaceTheme.values().length);
    }

    @Test
    public void eachEnumValueHasCorrectLabel() {
        assertEquals("GameBoy", InterfaceTheme.GAMEBOY.label);
        assertEquals("Dark GameBoy", InterfaceTheme.DARK_GAMEBOY.label);
        assertEquals("Atomic Purple", InterfaceTheme.ATOMIC_PURPLE.label);
        assertEquals("HackerBoy", InterfaceTheme.HACKERBOY.label);
    }

    // ---------------------------------------------------------------
    // current() and save() SharedPreferences tests
    // ---------------------------------------------------------------

    @Test
    public void current_returnsGameboyByDefault() {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals(InterfaceTheme.GAMEBOY, InterfaceTheme.current(context));
    }

    @Test
    public void current_returnsSavedTheme() {
        Context context = RuntimeEnvironment.getApplication();
        InterfaceTheme.save(context, InterfaceTheme.HACKERBOY);
        assertEquals(InterfaceTheme.HACKERBOY, InterfaceTheme.current(context));
    }

    // ---------------------------------------------------------------
    // isLightColor() tests
    // ---------------------------------------------------------------

    @Test
    public void isLightColor_white_returnsTrue() {
        assertTrue(InterfaceTheme.isLightColor(0xFFFFFFFF));
    }

    @Test
    public void isLightColor_black_returnsFalse() {
        assertFalse(InterfaceTheme.isLightColor(0xFF000000));
    }

    @Test
    public void isLightColor_gameboyBackground_returnsTrue() {
        // GameBoy cream 0xFFC4C4B4: brightness ~ 0.299*196 + 0.587*196 + 0.114*180 ≈ 194
        assertTrue(InterfaceTheme.isLightColor(InterfaceTheme.GAMEBOY.drawerBg));
    }

    @Test
    public void isLightColor_hackerBoyBackground_returnsFalse() {
        // HackerBoy 0xFF0D0D0D: brightness ~ 13
        assertFalse(InterfaceTheme.isLightColor(InterfaceTheme.HACKERBOY.drawerBg));
    }

    // ---------------------------------------------------------------
    // drawerBg / drawerAccent field tests
    // ---------------------------------------------------------------

    @Test
    public void allThemesHaveNonZeroDrawerColors() {
        for (InterfaceTheme theme : InterfaceTheme.values()) {
            assertTrue("drawerBg should be non-zero for " + theme.label,
                    theme.drawerBg != 0);
            assertTrue("drawerAccent should be non-zero for " + theme.label,
                    theme.drawerAccent != 0);
        }
    }

    @Test
    public void gameboyDrawerBg_matchesPanelBackground() {
        assertEquals(InterfaceTheme.GAMEBOY.background, InterfaceTheme.GAMEBOY.drawerBg);
    }

    // ---------------------------------------------------------------
    // current() and save() SharedPreferences tests (continued)
    // ---------------------------------------------------------------

    @Test
    public void current_returnsGameboyForInvalidThemeName() {
        Context context = RuntimeEnvironment.getApplication();
        // Write an invalid theme name directly into SharedPreferences
        context.getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(SettingsDialog.KEY_THEME, "NONEXISTENT_THEME").apply();
        assertEquals(InterfaceTheme.GAMEBOY, InterfaceTheme.current(context));
    }
}
