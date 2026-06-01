package com.voidterm.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.view.KeyEvent;

import com.voidterm.input.KeyGestureDetector.Gesture;
import com.voidterm.input.KeyGestureDetector.KeyId;
import com.voidterm.testutil.FakeScheduler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public class KeyGestureDetectorTest {

    private FakeScheduler scheduler;
    private List<String> events;
    private KeyGestureDetector detector;

    @Before
    public void setUp() {
        scheduler = new FakeScheduler();
        events = new ArrayList<>();
        detector = new KeyGestureDetector(scheduler, (key, gesture) -> events.add(key + ":" + gesture));
        detector.setTiming(GestureTiming.NORMAL);
    }

    private static KeyEvent ev(int repeat) {
        KeyEvent e = mock(KeyEvent.class);
        when(e.getRepeatCount()).thenReturn(repeat);
        return e;
    }

    private void armVolUp(Gesture... gestures) {
        Map<KeyId, EnumSet<Gesture>> armed = new EnumMap<>(KeyId.class);
        EnumSet<Gesture> set = EnumSet.noneOf(Gesture.class);
        for (Gesture g : gestures) set.add(g);
        armed.put(KeyId.VOL_UP, set);
        detector.setArmed(armed);
    }

    @Test
    public void notArmed_keyIsNotConsumed() {
        detector.setArmed(new EnumMap<>(KeyId.class));
        assertFalse(detector.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, ev(0)));
        assertFalse(detector.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, ev(0)));
    }

    @Test
    public void armedWithDouble_keyIsConsumed() {
        armVolUp(Gesture.DOUBLE);
        assertTrue(detector.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, ev(0)));
        assertTrue(detector.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, ev(0)));
    }

    @Test
    public void unrelatedKey_isNeverConsumed() {
        armVolUp(Gesture.DOUBLE);
        assertFalse(detector.onKeyDown(KeyEvent.KEYCODE_ENTER, ev(0)));
    }

    @Test
    public void repeatEvents_areSwallowedWithoutExtraGestures() {
        armVolUp(Gesture.LONG);
        assertTrue(detector.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, ev(0)));
        assertTrue(detector.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, ev(1))); // repeat swallowed
        // no gesture emitted yet
        assertEquals(0, events.size());
    }

    @Test
    public void reset_cancelsPendingTimers() {
        armVolUp(Gesture.DOUBLE);
        detector.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, ev(0));
        detector.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, ev(0)); // schedules multi-tap timeout
        detector.reset();
        scheduler.advance(1000);
        assertEquals(0, events.size()); // nothing fired after reset
    }
}
