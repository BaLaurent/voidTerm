package com.voidterm.input;

import android.view.KeyEvent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import com.voidterm.voice.VoiceInputManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link QuestInputHandler}.
 *
 * Uses Robolectric to shadow native library loading in VoiceInputManager,
 * and Mockito to mock VoiceInputManager and KeyEvent.
 */
@RunWith(RobolectricTestRunner.class)
public class QuestInputHandlerTest {

    private VoiceInputManager mockVoiceManager;
    private QuestInputHandler handler;

    @Before
    public void setUp() {
        mockVoiceManager = mock(VoiceInputManager.class);
        handler = new QuestInputHandler(mockVoiceManager);
    }

    private KeyEvent makeKeyEvent(int repeatCount) {
        KeyEvent event = mock(KeyEvent.class);
        when(event.getRepeatCount()).thenReturn(repeatCount);
        return event;
    }

    @Test
    public void onKeyDown_pttKey_callsPushToTalkPressed() {
        KeyEvent event = makeKeyEvent(0);

        boolean result = handler.onKeyDown(KeyEvent.KEYCODE_BUTTON_A, event);

        assertTrue(result);
        verify(mockVoiceManager).onPushToTalkPressed();
    }

    @Test
    public void onKeyUp_afterKeyDown_callsPushToTalkReleased() {
        KeyEvent downEvent = makeKeyEvent(0);
        KeyEvent upEvent = makeKeyEvent(0);

        handler.onKeyDown(KeyEvent.KEYCODE_BUTTON_A, downEvent);
        boolean result = handler.onKeyUp(KeyEvent.KEYCODE_BUTTON_A, upEvent);

        assertTrue(result);
        verify(mockVoiceManager).onPushToTalkReleased();
    }

    @Test
    public void onKeyDown_nonPttKey_returnsFalseAndNoInteraction() {
        KeyEvent event = makeKeyEvent(0);

        boolean result = handler.onKeyDown(KeyEvent.KEYCODE_BUTTON_B, event);

        assertFalse(result);
        verifyNoInteractions(mockVoiceManager);
    }

    @Test
    public void onKeyDown_repeatEvent_returnsTrueButNoPushToTalkPressed() {
        KeyEvent event = makeKeyEvent(1);

        boolean result = handler.onKeyDown(KeyEvent.KEYCODE_BUTTON_A, event);

        assertTrue(result);
        verify(mockVoiceManager, never()).onPushToTalkPressed();
    }

    @Test
    public void onKeyDown_nullVoiceManager_returnsFalse() {
        QuestInputHandler nullHandler = new QuestInputHandler(null);
        KeyEvent event = makeKeyEvent(0);

        boolean result = nullHandler.onKeyDown(KeyEvent.KEYCODE_BUTTON_A, event);

        assertFalse(result);
    }

    @Test
    public void onKeyUp_nullVoiceManager_returnsFalse() {
        QuestInputHandler nullHandler = new QuestInputHandler(null);
        KeyEvent event = makeKeyEvent(0);

        boolean result = nullHandler.onKeyUp(KeyEvent.KEYCODE_BUTTON_A, event);

        assertFalse(result);
    }

    @Test
    public void defaultPttKeycode_isButtonA() {
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, handler.getPttKeyCode());
    }

    @Test
    public void setPttKeyCode_changesTheTriggerKey() {
        handler.setPttKeyCode(KeyEvent.KEYCODE_BUTTON_X);
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, handler.getPttKeyCode());

        KeyEvent event = makeKeyEvent(0);

        // Old key no longer triggers
        assertFalse(handler.onKeyDown(KeyEvent.KEYCODE_BUTTON_A, event));

        // New key triggers
        assertTrue(handler.onKeyDown(KeyEvent.KEYCODE_BUTTON_X, event));
        verify(mockVoiceManager).onPushToTalkPressed();
    }

    @Test
    public void onKeyUp_withoutPriorKeyDown_returnsTrueButNoRelease() {
        KeyEvent event = makeKeyEvent(0);

        boolean result = handler.onKeyUp(KeyEvent.KEYCODE_BUTTON_A, event);

        assertTrue(result);
        verify(mockVoiceManager, never()).onPushToTalkReleased();
    }

    @Test
    public void doubleTap_twoRapidKeyDowns_callsOnDoubleTap() {
        KeyEvent firstEvent = makeKeyEvent(0);
        KeyEvent secondEvent = makeKeyEvent(0);

        // First press: sets lastPttPressTime, calls onPushToTalkPressed
        handler.onKeyDown(KeyEvent.KEYCODE_BUTTON_A, firstEvent);
        verify(mockVoiceManager).onPushToTalkPressed();

        // Second press within 300ms: detects double-tap, calls onDoubleTap
        boolean result = handler.onKeyDown(KeyEvent.KEYCODE_BUTTON_A, secondEvent);

        assertTrue(result);
        verify(mockVoiceManager).onDoubleTap();
    }
}
