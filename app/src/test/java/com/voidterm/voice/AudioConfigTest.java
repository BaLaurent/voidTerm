package com.voidterm.voice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AudioConfigTest {

    private static final float DELTA = 1e-4f;

    @Test
    public void defaults_correctValues() {
        AudioConfig config = AudioConfig.DEFAULT;
        assertEquals(1.0f, config.inputGain, DELTA);
        assertEquals(0.97f, config.preEmphasis, DELTA);
        assertEquals(80, config.hpCutoffHz);
        assertEquals(0.9f, config.normTarget, DELTA);
    }

    @Test
    public void hpAlpha_80Hz() {
        AudioConfig config = AudioConfig.DEFAULT;
        // Known value matching AudioPreprocessor.HP_ALPHA
        assertEquals(0.9695f, config.hpAlpha(), 0.001f);
    }

    @Test
    public void hpAlpha_40Hz_higherThan80Hz() {
        AudioConfig low = new AudioConfig(1.0f, 0.97f, 40, 0.9f);
        float alpha80 = AudioConfig.DEFAULT.hpAlpha();
        float alpha40 = low.hpAlpha();
        assertTrue("Lower cutoff should yield higher alpha", alpha40 > alpha80);
    }
}
