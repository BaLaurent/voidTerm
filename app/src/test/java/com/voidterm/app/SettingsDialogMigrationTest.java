package com.voidterm.app;

import com.voidterm.testutil.FakeSharedPreferences;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link SettingsDialog#isDirectSendEnabled} — the migration of the
 * legacy whisper-only "whisper_streaming" key to the engine-agnostic
 * "voice_direct_send" key. Pure logic, no Robolectric needed.
 */
public class SettingsDialogMigrationTest {

    private FakeSharedPreferences prefs;

    @Before
    public void setUp() {
        prefs = new FakeSharedPreferences();
    }

    @Test
    public void noKeysPresent_returnsFalse() {
        assertFalse(SettingsDialog.isDirectSendEnabled(prefs));
        // No migration write should have happened.
        assertFalse(prefs.contains(SettingsDialog.KEY_VOICE_DIRECT_SEND));
    }

    @Test
    public void legacyTrue_migratesToNewKeyAndRemovesOld() {
        prefs.edit().putBoolean(SettingsDialog.KEY_WHISPER_STREAMING, true).apply();

        assertTrue(SettingsDialog.isDirectSendEnabled(prefs));
        assertTrue(prefs.getBoolean(SettingsDialog.KEY_VOICE_DIRECT_SEND, false));
        assertFalse("legacy key must be removed after migration",
                prefs.contains(SettingsDialog.KEY_WHISPER_STREAMING));
    }

    @Test
    public void legacyFalse_migratesAsFalse() {
        prefs.edit().putBoolean(SettingsDialog.KEY_WHISPER_STREAMING, false).apply();

        assertFalse(SettingsDialog.isDirectSendEnabled(prefs));
        assertTrue("new key must exist after migration",
                prefs.contains(SettingsDialog.KEY_VOICE_DIRECT_SEND));
        assertFalse(prefs.contains(SettingsDialog.KEY_WHISPER_STREAMING));
    }

    @Test
    public void newKeyTakesPrecedenceOverLegacy() {
        // New key already set; a stale legacy key must be ignored, not migrated over.
        prefs.edit()
                .putBoolean(SettingsDialog.KEY_VOICE_DIRECT_SEND, true)
                .putBoolean(SettingsDialog.KEY_WHISPER_STREAMING, false)
                .apply();

        assertTrue(SettingsDialog.isDirectSendEnabled(prefs));
        // Legacy key untouched (migration short-circuits when the new key exists).
        assertTrue(prefs.contains(SettingsDialog.KEY_WHISPER_STREAMING));
    }
}
