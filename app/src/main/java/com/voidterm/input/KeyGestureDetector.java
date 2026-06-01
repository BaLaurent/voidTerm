package com.voidterm.input;

import android.view.KeyEvent;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * Recognizes multi-tap, long-press and combo gestures on the physical
 * Volume Up / Volume Down / Back keys. Decoupled from SharedPreferences and the
 * terminal; timing is driven by an injected {@link Scheduler} so the full state
 * machine is deterministically testable.
 *
 * onKeyDown/onKeyUp return true (consumed) iff the key is "intercepted": it has a
 * double/triple/long gesture armed, or (volume keys) the combo is armed. A key
 * with only its single-tap slot configured is NOT intercepted, so the caller
 * keeps its existing instant behavior. Once intercepted, the detector also owns
 * the SINGLE tap of that key.
 */
public final class KeyGestureDetector {

    public enum KeyId { VOL_UP, VOL_DOWN, BACK, COMBO }
    public enum Gesture { SINGLE, DOUBLE, TRIPLE, LONG }

    public interface GestureListener {
        void onGesture(KeyId key, Gesture gesture);
    }

    private final Scheduler scheduler;
    private final GestureListener listener;

    private Map<KeyId, EnumSet<Gesture>> armed = new EnumMap<>(KeyId.class);
    private GestureTiming timing = GestureTiming.NORMAL;

    private final Map<KeyId, KeyState> keyStates = new EnumMap<>(KeyId.class);

    // Combo coordination
    private boolean comboEngaged;
    private int comboTapCount;
    private Object comboMultiTapToken;
    private Object comboWindowToken;

    public KeyGestureDetector(Scheduler scheduler, GestureListener listener) {
        this.scheduler = scheduler;
        this.listener = listener;
        keyStates.put(KeyId.VOL_UP, new KeyState());
        keyStates.put(KeyId.VOL_DOWN, new KeyState());
        keyStates.put(KeyId.BACK, new KeyState());
    }

    public void setArmed(Map<KeyId, EnumSet<Gesture>> armed) {
        this.armed = (armed != null) ? armed : new EnumMap<>(KeyId.class);
    }

    public void setTiming(GestureTiming timing) {
        if (timing != null) this.timing = timing;
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        KeyId k = mapKey(keyCode);
        if (k == null || !isIntercepted(k)) return false;
        if (event.getRepeatCount() > 0) return true; // swallow auto-repeat
        handleDown(k);
        return true;
    }

    public boolean onKeyUp(int keyCode, KeyEvent event) {
        KeyId k = mapKey(keyCode);
        if (k == null || !isIntercepted(k)) return false;
        handleUp(k);
        return true;
    }

    public void reset() {
        for (KeyState s : keyStates.values()) {
            scheduler.cancel(s.multiTapToken);
            scheduler.cancel(s.longToken);
            s.reset();
        }
        scheduler.cancel(comboWindowToken);
        scheduler.cancel(comboMultiTapToken);
        comboWindowToken = null;
        comboMultiTapToken = null;
        comboEngaged = false;
        comboTapCount = 0;
    }

    private static KeyId mapKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP: return KeyId.VOL_UP;
            case KeyEvent.KEYCODE_VOLUME_DOWN: return KeyId.VOL_DOWN;
            case KeyEvent.KEYCODE_BACK: return KeyId.BACK;
            default: return null;
        }
    }

    private boolean comboArmed() {
        EnumSet<Gesture> g = armed.get(KeyId.COMBO);
        return g != null && !g.isEmpty();
    }

    private boolean hasExtraGesture(KeyId k) {
        EnumSet<Gesture> g = armed.get(k);
        return g != null && (g.contains(Gesture.DOUBLE)
                || g.contains(Gesture.TRIPLE) || g.contains(Gesture.LONG));
    }

    private boolean isIntercepted(KeyId k) {
        if (k == KeyId.BACK) return hasExtraGesture(KeyId.BACK);
        return hasExtraGesture(k) || comboArmed();
    }

    private boolean armedHas(KeyId k, Gesture g) {
        EnumSet<Gesture> set = armed.get(k);
        return set != null && set.contains(g);
    }

    private int maxArmedTaps(KeyId k) {
        if (armedHas(k, Gesture.TRIPLE)) return 3;
        if (armedHas(k, Gesture.DOUBLE)) return 2;
        return 1;
    }

    private void emitTap(KeyId k, int count) {
        Gesture g;
        if (count >= 3 && armedHas(k, Gesture.TRIPLE)) g = Gesture.TRIPLE;
        else if (count >= 2 && armedHas(k, Gesture.DOUBLE)) g = Gesture.DOUBLE;
        else g = Gesture.SINGLE;
        listener.onGesture(k, g);
    }

    private void handleDown(KeyId k) {
        beginKeyPress(k);
    }

    private void handleUp(KeyId k) {
        endKeyPress(k);
    }

    private void beginKeyPress(KeyId k) {
        KeyState s = keyStates.get(k);
        s.down = true;
        s.longFired = false;
        scheduler.cancel(s.multiTapToken); // continuing a tap sequence
        s.multiTapToken = null;
        // long-press timer added in Task 5
    }

    private void endKeyPress(KeyId k) {
        KeyState s = keyStates.get(k);
        s.down = false;
        if (s.longFired) { s.longFired = false; return; }
        scheduler.cancel(s.longToken);
        s.longToken = null;
        s.tapCount++;
        if (s.tapCount >= maxArmedTaps(k)) {
            emitTap(k, s.tapCount);
            s.tapCount = 0;
        } else {
            final int snapshot = s.tapCount;
            s.multiTapToken = scheduler.postDelayed(() -> {
                emitTap(k, snapshot);
                s.tapCount = 0;
                s.multiTapToken = null;
            }, timing.multiTapWindowMs);
        }
    }

    private static final class KeyState {
        boolean down;
        boolean longFired;
        boolean comboPending;
        int tapCount;
        Object multiTapToken;
        Object longToken;

        void reset() {
            down = false;
            longFired = false;
            comboPending = false;
            tapCount = 0;
            multiTapToken = null;
            longToken = null;
        }
    }
}
