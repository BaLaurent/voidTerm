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

    private void tap(int keyCode) {
        detector.onKeyDown(keyCode, ev(0));
        detector.onKeyUp(keyCode, ev(0));
    }

    @Test
    public void singleArmed_emitsSingleAfterWindow() {
        armVolUp(Gesture.DOUBLE); // double armed -> single must wait
        tap(KeyEvent.KEYCODE_VOLUME_UP);
        assertEquals(0, events.size());            // waiting for a possible 2nd tap
        scheduler.advance(GestureTiming.NORMAL.multiTapWindowMs);
        assertEquals(1, events.size());
        assertEquals("VOL_UP:SINGLE", events.get(0));
    }

    @Test
    public void doubleTap_emitsDoubleImmediatelyAtMax() {
        armVolUp(Gesture.DOUBLE);
        tap(KeyEvent.KEYCODE_VOLUME_UP);
        tap(KeyEvent.KEYCODE_VOLUME_UP);
        assertEquals(1, events.size());            // max reached -> immediate
        assertEquals("VOL_UP:DOUBLE", events.get(0));
    }

    @Test
    public void tripleTap_emitsTriple() {
        armVolUp(Gesture.DOUBLE, Gesture.TRIPLE);
        tap(KeyEvent.KEYCODE_VOLUME_UP);
        tap(KeyEvent.KEYCODE_VOLUME_UP);
        tap(KeyEvent.KEYCODE_VOLUME_UP);
        assertEquals(1, events.size());
        assertEquals("VOL_UP:TRIPLE", events.get(0));
    }

    @Test
    public void doubleArmed_singleThenWait_emitsSingleOnly() {
        armVolUp(Gesture.DOUBLE);
        tap(KeyEvent.KEYCODE_VOLUME_UP);
        scheduler.advance(GestureTiming.NORMAL.multiTapWindowMs);
        assertEquals(1, events.size());
        assertEquals("VOL_UP:SINGLE", events.get(0));
    }

    @Test
    public void longPress_firesAtThreshold() {
        armVolUp(Gesture.LONG);
        detector.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, ev(0));
        scheduler.advance(GestureTiming.NORMAL.longPressMs);
        assertEquals(1, events.size());
        assertEquals("VOL_UP:LONG", events.get(0));
    }

    @Test
    public void longPress_releaseAfterFire_emitsNoTap() {
        armVolUp(Gesture.LONG);
        detector.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, ev(0));
        scheduler.advance(GestureTiming.NORMAL.longPressMs);
        detector.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, ev(0));
        scheduler.advance(1000);
        assertEquals(1, events.size()); // only the LONG, no SINGLE
    }

    @Test
    public void quickRelease_beforeThreshold_isATap() {
        armVolUp(Gesture.LONG); // long armed, no multi-tap -> max taps = 1
        tap(KeyEvent.KEYCODE_VOLUME_UP);
        assertEquals(1, events.size());
        assertEquals("VOL_UP:SINGLE", events.get(0)); // emitted immediately (max=1)
    }

    private void armCombo(Gesture... gestures) {
        Map<KeyId, EnumSet<Gesture>> armed = new EnumMap<>(KeyId.class);
        EnumSet<Gesture> set = EnumSet.noneOf(Gesture.class);
        for (Gesture g : gestures) set.add(g);
        armed.put(KeyId.COMBO, set);
        detector.setArmed(armed);
    }

    @Test
    public void combo_bothKeysWithinWindow_emitsComboSingle() {
        armCombo(Gesture.SINGLE);
        detector.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, ev(0));
        detector.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, ev(0)); // within window
        detector.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, ev(0));
        detector.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN, ev(0));
        assertEquals(1, events.size());
        assertEquals("COMBO:SINGLE", events.get(0));
    }

    @Test
    public void combo_doubleTap_emitsComboDouble() {
        armCombo(Gesture.SINGLE, Gesture.DOUBLE);
        // first combo press
        detector.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, ev(0));
        detector.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, ev(0));
        detector.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, ev(0));
        detector.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN, ev(0));
        // second combo press, within multi-tap window
        detector.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, ev(0));
        detector.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, ev(0));
        detector.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, ev(0));
        detector.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN, ev(0));
        assertEquals(1, events.size());
        assertEquals("COMBO:DOUBLE", events.get(0));
    }

    @Test
    public void combo_windowExpiresAlone_fallsBackToSingleKey() {
        // Combo armed AND Vol+ has its own double-tap armed.
        Map<KeyId, EnumSet<Gesture>> armed = new EnumMap<>(KeyId.class);
        armed.put(KeyId.COMBO, EnumSet.of(Gesture.SINGLE));
        armed.put(KeyId.VOL_UP, EnumSet.of(Gesture.DOUBLE));
        detector.setArmed(armed);

        detector.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, ev(0));
        scheduler.advance(GestureTiming.NORMAL.comboWindowMs); // window expires, no partner
        detector.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, ev(0));    // -> individual tap
        scheduler.advance(GestureTiming.NORMAL.multiTapWindowMs);
        assertEquals(1, events.size());
        assertEquals("VOL_UP:SINGLE", events.get(0));
    }

    @Test
    public void combo_releaseDuringWindow_resolvesAsSingleKeyTap() {
        Map<KeyId, EnumSet<Gesture>> armed = new EnumMap<>(KeyId.class);
        armed.put(KeyId.COMBO, EnumSet.of(Gesture.SINGLE));
        armed.put(KeyId.VOL_DOWN, EnumSet.of(Gesture.DOUBLE));
        detector.setArmed(armed);

        detector.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, ev(0));
        detector.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN, ev(0)); // released inside window
        scheduler.advance(GestureTiming.NORMAL.comboWindowMs);  // window expires
        scheduler.advance(GestureTiming.NORMAL.multiTapWindowMs);
        assertEquals(1, events.size());
        assertEquals("VOL_DOWN:SINGLE", events.get(0));
    }
}
