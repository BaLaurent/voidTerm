package com.voidterm.voice;

import com.voidterm.app.SettingsDialog;
import com.voidterm.testutil.FakeSharedPreferences;

import org.junit.Before;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link DeviceProfiler} static utility methods.
 *
 * Uses {@link FakeSharedPreferences} to avoid Robolectric.
 * Does NOT test {@code profile()} — it requires WhisperBridge native libs.
 */
public class DeviceProfilerTest {

    private FakeSharedPreferences prefs;

    @Before
    public void setUp() {
        prefs = new FakeSharedPreferences();
    }

    // ---------------------------------------------------------------
    // generateBenchmarkAudio()
    // ---------------------------------------------------------------

    @Test
    public void generateBenchmarkAudio_returnsArrayOfLength16000() {
        float[] audio = DeviceProfiler.generateBenchmarkAudio();
        assertEquals(16000, audio.length);
    }

    @Test
    public void generateBenchmarkAudio_allValuesInRange() {
        float[] audio = DeviceProfiler.generateBenchmarkAudio();
        for (int i = 0; i < audio.length; i++) {
            assertTrue("Value at index " + i + " out of range: " + audio[i],
                    audio[i] >= -1.0f && audio[i] <= 1.0f);
        }
    }

    @Test
    public void generateBenchmarkAudio_notAllSilence() {
        float[] audio = DeviceProfiler.generateBenchmarkAudio();
        boolean hasNonZero = false;
        for (float sample : audio) {
            if (sample != 0.0f) {
                hasNonZero = true;
                break;
            }
        }
        assertTrue("Audio should contain non-zero samples", hasNonZero);
    }

    @Test
    public void generateBenchmarkAudio_sinusoidalShape() {
        float[] audio = DeviceProfiler.generateBenchmarkAudio();
        // sin(0) = 0
        assertEquals(0.0f, audio[0], 1e-6f);
        // Values should oscillate: some positive, some negative
        boolean hasPositive = false;
        boolean hasNegative = false;
        for (float sample : audio) {
            if (sample > 0.1f) hasPositive = true;
            if (sample < -0.1f) hasNegative = true;
        }
        assertTrue("Sine wave should have positive values", hasPositive);
        assertTrue("Sine wave should have negative values", hasNegative);
    }

    // ---------------------------------------------------------------
    // needsProfiling()
    // ---------------------------------------------------------------

    @Test
    public void needsProfiling_noCachedModel_returnsTrue() {
        assertTrue(DeviceProfiler.needsProfiling(prefs, "ggml-base.bin"));
    }

    @Test
    public void needsProfiling_differentModel_returnsTrue() {
        prefs.edit().putString(DeviceProfiler.KEY_AUTOTUNE_MODEL, "ggml-tiny.bin").apply();
        assertTrue(DeviceProfiler.needsProfiling(prefs, "ggml-base.bin"));
    }

    @Test
    public void needsProfiling_sameModel_returnsFalse() {
        prefs.edit().putString(DeviceProfiler.KEY_AUTOTUNE_MODEL, "ggml-base.bin").apply();
        assertFalse(DeviceProfiler.needsProfiling(prefs, "ggml-base.bin"));
    }

    // ---------------------------------------------------------------
    // applyDefaults()
    // ---------------------------------------------------------------

    @Test
    public void applyDefaults_fastTier_setsExpectedValues() {
        DeviceProfiler.Result result = new DeviceProfiler.Result(DeviceProfiler.Tier.FAST, 400);
        DeviceProfiler.applyDefaults(prefs, "model.bin", result);

        assertTrue(prefs.getBoolean(SettingsDialog.KEY_WHISPER_BEAM_SEARCH, false));
        assertEquals(5, prefs.getInt(SettingsDialog.KEY_WHISPER_BEAM_SIZE, 0));
        assertTrue(prefs.getBoolean(SettingsDialog.KEY_WHISPER_PROPORTIONAL_CONTEXT, false));
        assertTrue(prefs.getBoolean(SettingsDialog.KEY_WHISPER_SUPPRESS_NON_SPEECH, false));
    }

    @Test
    public void applyDefaults_mediumTier_setsBeamSearchTrueBeamSize3() {
        DeviceProfiler.Result result = new DeviceProfiler.Result(DeviceProfiler.Tier.MEDIUM, 800);
        DeviceProfiler.applyDefaults(prefs, "model.bin", result);

        assertTrue(prefs.getBoolean(SettingsDialog.KEY_WHISPER_BEAM_SEARCH, false));
        assertEquals(3, prefs.getInt(SettingsDialog.KEY_WHISPER_BEAM_SIZE, 0));
    }

    @Test
    public void applyDefaults_slowTier_setsBeamSearchFalse() {
        DeviceProfiler.Result result = new DeviceProfiler.Result(DeviceProfiler.Tier.SLOW, 1500);
        DeviceProfiler.applyDefaults(prefs, "model.bin", result);

        assertFalse(prefs.getBoolean(SettingsDialog.KEY_WHISPER_BEAM_SEARCH, true));
        assertEquals(3, prefs.getInt(SettingsDialog.KEY_WHISPER_BEAM_SIZE, 0));
    }

    @Test
    public void applyDefaults_userOverrideRespected() {
        // Mark beam_search as user-overridden
        Set<String> overrides = new HashSet<>();
        overrides.add(SettingsDialog.KEY_WHISPER_BEAM_SEARCH);
        prefs.edit().putStringSet(DeviceProfiler.KEY_USER_OVERRIDES, overrides).apply();

        // Set a user value that differs from what FAST tier would write
        prefs.edit().putBoolean(SettingsDialog.KEY_WHISPER_BEAM_SEARCH, false).apply();

        DeviceProfiler.Result result = new DeviceProfiler.Result(DeviceProfiler.Tier.FAST, 400);
        DeviceProfiler.applyDefaults(prefs, "model.bin", result);

        // beam_search should remain false (user override), not overwritten to true
        assertFalse(prefs.getBoolean(SettingsDialog.KEY_WHISPER_BEAM_SEARCH, true));
    }

    @Test
    public void applyDefaults_proportionalContextAlwaysTrue() {
        // Test all three tiers
        for (DeviceProfiler.Tier tier : DeviceProfiler.Tier.values()) {
            FakeSharedPreferences tierPrefs = new FakeSharedPreferences();
            DeviceProfiler.Result result = new DeviceProfiler.Result(tier, 500);
            DeviceProfiler.applyDefaults(tierPrefs, "model.bin", result);
            assertTrue("proportional_context should be true for " + tier,
                    tierPrefs.getBoolean(SettingsDialog.KEY_WHISPER_PROPORTIONAL_CONTEXT, false));
        }
    }

    // ---------------------------------------------------------------
    // migrateIfNeeded()
    // ---------------------------------------------------------------

    @Test
    public void migrateIfNeeded_changedBeamSearch_addedToOverrides() {
        // Simulate user having changed beam_search to true (old default was false)
        prefs.edit().putBoolean(SettingsDialog.KEY_WHISPER_BEAM_SEARCH, true).apply();

        DeviceProfiler.migrateIfNeeded(prefs);

        Set<String> overrides = prefs.getStringSet(DeviceProfiler.KEY_USER_OVERRIDES, new HashSet<>());
        assertTrue("beam_search should be in user overrides",
                overrides.contains(SettingsDialog.KEY_WHISPER_BEAM_SEARCH));
        assertTrue(prefs.getBoolean(DeviceProfiler.KEY_AUTOTUNE_MIGRATED, false));
    }

    @Test
    public void migrateIfNeeded_alreadyMigrated_skips() {
        prefs.edit().putBoolean(DeviceProfiler.KEY_AUTOTUNE_MIGRATED, true).apply();
        prefs.edit().putBoolean(SettingsDialog.KEY_WHISPER_BEAM_SEARCH, true).apply();

        DeviceProfiler.migrateIfNeeded(prefs);

        // Should not have created overrides since migration was skipped
        Set<String> overrides = prefs.getStringSet(DeviceProfiler.KEY_USER_OVERRIDES, new HashSet<>());
        assertTrue("Overrides should be empty after skipped migration", overrides.isEmpty());
    }

    @Test
    public void migrateIfNeeded_noChangesFromDefaults_setsMigratedFlagOnly() {
        // All values match old defaults (or are absent) — no overrides should be added
        DeviceProfiler.migrateIfNeeded(prefs);

        assertTrue("Migrated flag should be set",
                prefs.getBoolean(DeviceProfiler.KEY_AUTOTUNE_MIGRATED, false));
        Set<String> overrides = prefs.getStringSet(DeviceProfiler.KEY_USER_OVERRIDES, new HashSet<>());
        assertTrue("No overrides should be added when defaults match", overrides.isEmpty());
    }

    // ---------------------------------------------------------------
    // markUserOverride()
    // ---------------------------------------------------------------

    @Test
    public void markUserOverride_validAutotuneKey_addedToOverrides() {
        DeviceProfiler.markUserOverride(prefs, SettingsDialog.KEY_WHISPER_BEAM_SEARCH);

        Set<String> overrides = prefs.getStringSet(DeviceProfiler.KEY_USER_OVERRIDES, new HashSet<>());
        assertTrue(overrides.contains(SettingsDialog.KEY_WHISPER_BEAM_SEARCH));
    }

    @Test
    public void markUserOverride_nonAutotuneKey_ignored() {
        DeviceProfiler.markUserOverride(prefs, SettingsDialog.KEY_WHISPER_LANGUAGE);

        Set<String> overrides = prefs.getStringSet(DeviceProfiler.KEY_USER_OVERRIDES, new HashSet<>());
        assertTrue("Non-autotune key should not be added", overrides.isEmpty());
    }

    // ---------------------------------------------------------------
    // resetToAuto()
    // ---------------------------------------------------------------

    @Test
    public void resetToAuto_clearsOverridesAndCachedModel() {
        // Set up state that should be cleared
        Set<String> overrides = new HashSet<>();
        overrides.add(SettingsDialog.KEY_WHISPER_BEAM_SEARCH);
        prefs.edit()
                .putStringSet(DeviceProfiler.KEY_USER_OVERRIDES, overrides)
                .putString(DeviceProfiler.KEY_AUTOTUNE_MODEL, "ggml-base.bin")
                .apply();

        DeviceProfiler.resetToAuto(prefs);

        assertFalse("user_overrides should be removed", prefs.contains(DeviceProfiler.KEY_USER_OVERRIDES));
        assertFalse("autotune_model should be removed", prefs.contains(DeviceProfiler.KEY_AUTOTUNE_MODEL));
    }
}
