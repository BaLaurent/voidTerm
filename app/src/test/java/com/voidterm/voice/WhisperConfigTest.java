package com.voidterm.voice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link WhisperConfig} immutable data class.
 */
public class WhisperConfigTest {

    @Test
    public void constructor_storesAllFieldsCorrectly() {
        WhisperConfig config = new WhisperConfig(
                "ja", true, "medical terms", 0.5f,
                true, 8, 6, true, false, true
        );

        assertEquals("ja", config.language);
        assertTrue(config.translate);
        assertEquals("medical terms", config.initialPrompt);
        assertEquals(0.5f, config.temperature, 0.0001f);
        assertTrue(config.useBeamSearch);
        assertEquals(8, config.beamSize);
        assertEquals(6, config.threadCount);
        assertTrue(config.suppressNonSpeech);
        assertFalse(config.useProportionalContext);
        assertTrue(config.streaming);
    }

    @Test
    public void constructor_defaultLikeValues_storedCorrectly() {
        WhisperConfig config = new WhisperConfig(
                "en", false, "", 0.0f,
                false, 5, 4, false, true, false
        );

        assertEquals("en", config.language);
        assertFalse(config.translate);
        assertEquals("", config.initialPrompt);
        assertEquals(0.0f, config.temperature, 0.0001f);
        assertFalse(config.useBeamSearch);
        assertEquals(5, config.beamSize);
        assertEquals(4, config.threadCount);
        assertFalse(config.suppressNonSpeech);
        assertTrue(config.useProportionalContext);
        assertFalse(config.streaming);
    }

    @Test
    public void constructor_nullableFields_acceptNull() {
        WhisperConfig config = new WhisperConfig(
                null, false, null, 0.0f,
                false, 5, 4, false, true, false
        );

        assertNull(config.language);
        assertNull(config.initialPrompt);
    }
}
