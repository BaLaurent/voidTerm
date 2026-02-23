package com.voidterm.voice;

/**
 * Immutable configuration for audio preprocessing parameters.
 * Cached in VoiceInputManager and invalidated on SharedPreferences change.
 */
public class AudioConfig {
    public final float inputGain;
    public final float preEmphasis;
    public final int hpCutoffHz;
    public final float normTarget;

    public AudioConfig(float inputGain, float preEmphasis, int hpCutoffHz, float normTarget) {
        this.inputGain = inputGain;
        this.preEmphasis = preEmphasis;
        this.hpCutoffHz = hpCutoffHz;
        this.normTarget = normTarget;
    }

    /**
     * Compute high-pass filter alpha coefficient from cutoff frequency.
     * α = RC / (RC + dt), where RC = 1/(2π·fc) and dt = 1/fs
     */
    public float hpAlpha() {
        double rc = 1.0 / (2.0 * Math.PI * hpCutoffHz);
        double dt = 1.0 / AudioCapture.SAMPLE_RATE;
        return (float) (rc / (rc + dt));
    }

    public static final AudioConfig DEFAULT = new AudioConfig(1.0f, 0.97f, 80, 0.9f);
}
