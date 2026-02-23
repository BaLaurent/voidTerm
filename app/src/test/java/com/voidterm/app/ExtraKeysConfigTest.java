package com.voidterm.app;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import com.voidterm.contracts.VoiceState;
import com.voidterm.voice.VoiceInputManager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExtraKeysConfig}.
 *
 * Uses Robolectric to shadow native library loading in VoiceInputManager,
 * and Mockito to mock VoiceInputManager behavior.
 */
@RunWith(RobolectricTestRunner.class)
public class ExtraKeysConfigTest {

    private VoiceInputManager mockVoiceManager;
    private ExtraKeysConfig config;

    @Before
    public void setUp() {
        mockVoiceManager = mock(VoiceInputManager.class);
        config = new ExtraKeysConfig(mockVoiceManager);
    }

    @Test
    public void onExtraKeyPressed_nonMicKey_returnsFalseAndNoInteraction() {
        boolean result = config.onExtraKeyPressed("ESC");

        assertFalse(result);
        verifyNoInteractions(mockVoiceManager);
    }

    @Test
    public void onExtraKeyPressed_micKeyInIdleState_callsPushToTalkPressed() {
        when(mockVoiceManager.getCurrentState()).thenReturn(VoiceState.IDLE);

        boolean result = config.onExtraKeyPressed(ExtraKeysConfig.MIC_BUTTON_KEY);

        assertTrue(result);
        verify(mockVoiceManager).onPushToTalkPressed();
        verify(mockVoiceManager, never()).onPushToTalkReleased();
    }

    @Test
    public void onExtraKeyPressed_micKeyInRecordingState_callsPushToTalkReleased() {
        when(mockVoiceManager.getCurrentState()).thenReturn(VoiceState.RECORDING);

        boolean result = config.onExtraKeyPressed(ExtraKeysConfig.MIC_BUTTON_KEY);

        assertTrue(result);
        verify(mockVoiceManager).onPushToTalkReleased();
        verify(mockVoiceManager, never()).onPushToTalkPressed();
    }

    @Test
    public void onExtraKeyPressed_micKeyInTranscribingState_noPressOrReleaseCalled() {
        when(mockVoiceManager.getCurrentState()).thenReturn(VoiceState.TRANSCRIBING);

        boolean result = config.onExtraKeyPressed(ExtraKeysConfig.MIC_BUTTON_KEY);

        assertTrue(result);
        verify(mockVoiceManager, never()).onPushToTalkPressed();
        verify(mockVoiceManager, never()).onPushToTalkReleased();
    }

    @Test
    public void onExtraKeyPressed_nullVoiceManager_returnsTrueWithoutNpe() {
        ExtraKeysConfig nullConfig = new ExtraKeysConfig(null);

        boolean result = nullConfig.onExtraKeyPressed(ExtraKeysConfig.MIC_BUTTON_KEY);

        assertTrue(result);
    }

    @Test
    public void isMicRecording_recordingState_returnsTrue() {
        when(mockVoiceManager.getCurrentState()).thenReturn(VoiceState.RECORDING);

        assertTrue(config.isMicRecording());
    }

    @Test
    public void isMicRecording_idleState_returnsFalse() {
        when(mockVoiceManager.getCurrentState()).thenReturn(VoiceState.IDLE);

        assertFalse(config.isMicRecording());
    }

    @Test
    public void isMicRecording_nullVoiceManager_returnsFalse() {
        ExtraKeysConfig nullConfig = new ExtraKeysConfig(null);

        assertFalse(nullConfig.isMicRecording());
    }
}
