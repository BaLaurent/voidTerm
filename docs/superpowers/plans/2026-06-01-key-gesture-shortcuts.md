# Raccourcis avancés sur touches physiques — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ajouter des gestes (double tap, triple tap, appui long, et combo Vol+&Vol−) sur les touches physiques Volume+, Volume− et Back, chaque geste mappable sur le jeu d'actions existant (Escape / Toggle clavier / Voice / Macro / Défaut).

**Architecture:** Un module `KeyGestureDetector` (mécanisme) encapsule une machine à états temporisée, pilotée par un `Scheduler` injecté (testable sans device). `TermuxActivity` (policy) lui transmet `onKeyDown`/`onKeyUp`, reçoit un geste résolu et le dispatche via les `SharedPreferences`. La décision « consommer ou non » vit dans le détecteur (donc dans la zone testée). Aucune migration : les 3 clés de prefs existantes restent le slot « simple tap ».

**Tech Stack:** Java 17, Android (API 34), JUnit 4 + Mockito + Robolectric, `SharedPreferences`, `AudioManager`.

**Spec de référence:** `docs/superpowers/specs/2026-06-01-key-gesture-shortcuts-design.md`

---

## Structure des fichiers

**Créés (`com.voidterm.input`)**
- `Scheduler.java` — interface de planification (frontière temporelle, pour la testabilité).
- `HandlerScheduler.java` — impl. production, wrap un `android.os.Handler`.
- `GestureTiming.java` — config immuable (3 délais) + `fromPreset(String)`.
- `KeyGestureDetector.java` — la machine à états + enums `KeyId`/`Gesture` + interface `GestureListener`.

**Créés (tests)**
- `app/src/test/java/com/voidterm/testutil/FakeScheduler.java` — `Scheduler` à temps virtuel.
- `app/src/test/java/com/voidterm/input/GestureTimingTest.java`
- `app/src/test/java/com/voidterm/input/KeyGestureDetectorTest.java`

**Modifiés**
- `SettingsDialog.java` — constantes de clés des 12 nouveaux slots + preset de timing + labels.
- `SettingsActivity.java` — fabrique `addGestureRow`, spinner de sensibilité, 12 slots, refactor des lignes existantes.
- `TermuxActivity.java` — instanciation du détecteur, `dispatchKeyAction` extrait, émulation volume, câblage `onKeyDown`/`onKeyUp`/`onResume`/`onPause`.

---

## Task 1: `GestureTiming` (config immuable + presets)

**Files:**
- Create: `app/src/main/java/com/voidterm/input/GestureTiming.java`
- Test: `app/src/test/java/com/voidterm/input/GestureTimingTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.voidterm.input;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GestureTimingTest {

    @Test
    public void fromPreset_fast_returnsFastValues() {
        GestureTiming t = GestureTiming.fromPreset("fast");
        assertEquals(200, t.multiTapWindowMs);
        assertEquals(400, t.longPressMs);
        assertEquals(50, t.comboWindowMs);
    }

    @Test
    public void fromPreset_slow_returnsSlowValues() {
        GestureTiming t = GestureTiming.fromPreset("slow");
        assertEquals(400, t.multiTapWindowMs);
        assertEquals(700, t.longPressMs);
        assertEquals(90, t.comboWindowMs);
    }

    @Test
    public void fromPreset_normalOrUnknown_returnsNormal() {
        assertEquals(280, GestureTiming.fromPreset("normal").multiTapWindowMs);
        assertEquals(280, GestureTiming.fromPreset("garbage").multiTapWindowMs);
        assertEquals(280, GestureTiming.fromPreset(null).multiTapWindowMs);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.input.GestureTimingTest"`
Expected: FAIL — `GestureTiming` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.voidterm.input;

/**
 * Immutable gesture timing. Built from a 3-level preset so users never type
 * raw milliseconds. Follows the SharedPreferences -> immutable config pattern
 * used by WhisperConfig / AudioConfig.
 */
public final class GestureTiming {
    public final long multiTapWindowMs;
    public final long longPressMs;
    public final long comboWindowMs;

    public GestureTiming(long multiTapWindowMs, long longPressMs, long comboWindowMs) {
        this.multiTapWindowMs = multiTapWindowMs;
        this.longPressMs = longPressMs;
        this.comboWindowMs = comboWindowMs;
    }

    public static final GestureTiming FAST = new GestureTiming(200, 400, 50);
    public static final GestureTiming NORMAL = new GestureTiming(280, 500, 60);
    public static final GestureTiming SLOW = new GestureTiming(400, 700, 90);

    public static GestureTiming fromPreset(String preset) {
        if ("fast".equals(preset)) return FAST;
        if ("slow".equals(preset)) return SLOW;
        return NORMAL;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.input.GestureTimingTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voidterm/input/GestureTiming.java app/src/test/java/com/voidterm/input/GestureTimingTest.java
git commit -m "feat(input): GestureTiming immutable config with 3-level presets"
```

---

## Task 2: `Scheduler` + `HandlerScheduler` + `FakeScheduler`

The detector never calls `Handler` directly. It depends on a `Scheduler` seam — production uses a `Handler`, tests use a virtual-time fake. This is a time boundary (testability exception): it makes the entire state machine deterministic under unit tests.

**Files:**
- Create: `app/src/main/java/com/voidterm/input/Scheduler.java`
- Create: `app/src/main/java/com/voidterm/input/HandlerScheduler.java`
- Create: `app/src/test/java/com/voidterm/testutil/FakeScheduler.java`
- Test: `app/src/test/java/com/voidterm/testutil/FakeSchedulerTest.java`

- [ ] **Step 1: Write the failing test for the fake**

```java
package com.voidterm.testutil;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class FakeSchedulerTest {

    @Test
    public void runsTaskExactlyAtItsDelay() {
        FakeScheduler s = new FakeScheduler();
        List<String> log = new ArrayList<>();
        s.postDelayed(() -> log.add("fired"), 100);

        s.advance(99);
        assertEquals(0, log.size());
        s.advance(1);
        assertEquals(1, log.size());
    }

    @Test
    public void cancelledTaskNeverRuns() {
        FakeScheduler s = new FakeScheduler();
        List<String> log = new ArrayList<>();
        Object token = s.postDelayed(() -> log.add("fired"), 50);
        s.cancel(token);
        s.advance(100);
        assertEquals(0, log.size());
    }

    @Test
    public void taskRescheduledFromInsideRunsLater() {
        FakeScheduler s = new FakeScheduler();
        List<String> log = new ArrayList<>();
        s.postDelayed(() -> {
            log.add("first");
            s.postDelayed(() -> log.add("second"), 50);
        }, 50);
        s.advance(50);
        assertEquals(1, log.size()); // only "first" so far
        s.advance(50);
        assertEquals(2, log.size()); // "second" now fired
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.testutil.FakeSchedulerTest"`
Expected: FAIL — `FakeScheduler` does not exist.

- [ ] **Step 3: Write the `Scheduler` interface**

```java
package com.voidterm.input;

/**
 * Minimal time-delay seam so the gesture state machine is testable.
 * Production wraps a Handler; tests use a virtual-time fake.
 */
public interface Scheduler {
    /** Schedule {@code r} to run after {@code delayMs}; returns a cancellation token. */
    Object postDelayed(Runnable r, long delayMs);

    /** Cancel a previously scheduled runnable. No-op on null/unknown tokens. */
    void cancel(Object token);
}
```

- [ ] **Step 4: Write the `HandlerScheduler` production impl**

```java
package com.voidterm.input;

import android.os.Handler;

/** Production {@link Scheduler} backed by an Android Handler. */
public final class HandlerScheduler implements Scheduler {
    private final Handler handler;

    public HandlerScheduler(Handler handler) {
        this.handler = handler;
    }

    @Override
    public Object postDelayed(Runnable r, long delayMs) {
        handler.postDelayed(r, delayMs);
        return r;
    }

    @Override
    public void cancel(Object token) {
        if (token instanceof Runnable) {
            handler.removeCallbacks((Runnable) token);
        }
    }
}
```

- [ ] **Step 5: Write the `FakeScheduler` test util**

```java
package com.voidterm.testutil;

import com.voidterm.input.Scheduler;

import java.util.ArrayList;
import java.util.List;

/** Virtual-time {@link Scheduler} for deterministic unit tests. */
public final class FakeScheduler implements Scheduler {

    private static final class Task {
        final Runnable r;
        long remaining;
        boolean cancelled;
        Task(Runnable r, long remaining) { this.r = r; this.remaining = remaining; }
    }

    private final List<Task> tasks = new ArrayList<>();

    @Override
    public Object postDelayed(Runnable r, long delayMs) {
        Task t = new Task(r, delayMs);
        tasks.add(t);
        return t;
    }

    @Override
    public void cancel(Object token) {
        if (token instanceof Task) {
            ((Task) token).cancelled = true;
        }
    }

    /** Advance virtual time by {@code ms}, running every task whose delay elapses. */
    public void advance(long ms) {
        long left = ms;
        while (left > 0) {
            Task next = earliestActive();
            if (next == null || next.remaining > left) {
                decrementActive(left);
                break;
            }
            decrementActive(next.remaining);
            left -= next.remaining;
            next.cancelled = true; // consumed
            next.r.run();          // may post new tasks
            purgeCancelled();
        }
        purgeCancelled();
    }

    private Task earliestActive() {
        Task best = null;
        for (Task t : tasks) {
            if (!t.cancelled && (best == null || t.remaining < best.remaining)) {
                best = t;
            }
        }
        return best;
    }

    private void decrementActive(long delta) {
        for (Task t : tasks) {
            if (!t.cancelled) t.remaining -= delta;
        }
    }

    private void purgeCancelled() {
        tasks.removeIf(t -> t.cancelled);
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.testutil.FakeSchedulerTest"`
Expected: PASS (3 tests).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/voidterm/input/Scheduler.java app/src/main/java/com/voidterm/input/HandlerScheduler.java app/src/test/java/com/voidterm/testutil/FakeScheduler.java app/src/test/java/com/voidterm/testutil/FakeSchedulerTest.java
git commit -m "feat(input): Scheduler seam (Handler-backed + virtual-time fake)"
```

---

## Task 3: `KeyGestureDetector` — squelette + contrat de consommation

This task creates the class with its enums, fields, public API, the key-mapping / interception logic, and `reset()`. The tap/long/combo bodies come in Tasks 4–6, but the consumption contract (what `onKeyDown`/`onKeyUp` return) is finalized **here** and tested, because that is the safety-critical part (a non-consumed Back closes the app).

**Files:**
- Create: `app/src/main/java/com/voidterm/input/KeyGestureDetector.java`
- Test: `app/src/test/java/com/voidterm/input/KeyGestureDetectorTest.java`

- [ ] **Step 1: Write the failing test (consumption contract)**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.input.KeyGestureDetectorTest"`
Expected: FAIL — `KeyGestureDetector` does not exist.

- [ ] **Step 3: Write the class (skeleton: enums, fields, API, interception, reset, empty handlers)**

```java
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

    // Bodies implemented in Tasks 4-6.
    private void handleDown(KeyId k) {
    }

    private void handleUp(KeyId k) {
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.input.KeyGestureDetectorTest"`
Expected: PASS (5 tests). `handleDown`/`handleUp` are empty, so no gesture fires — which matches every assertion in this task (they only check return values, repeat-swallow, and reset).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voidterm/input/KeyGestureDetector.java app/src/test/java/com/voidterm/input/KeyGestureDetectorTest.java
git commit -m "feat(input): KeyGestureDetector skeleton + consumption contract"
```

---

## Task 4: Multi-tap (SINGLE / DOUBLE / TRIPLE) sur une touche

**Files:**
- Modify: `app/src/main/java/com/voidterm/input/KeyGestureDetector.java` (fill `handleDown`/`handleUp`, add `beginKeyPress`/`endKeyPress`)
- Test: `app/src/test/java/com/voidterm/input/KeyGestureDetectorTest.java`

- [ ] **Step 1: Add failing tests**

Append these methods to `KeyGestureDetectorTest`:

```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.input.KeyGestureDetectorTest"`
Expected: FAIL — empty handlers emit nothing.

- [ ] **Step 3: Implement `handleDown`/`handleUp` + helpers**

Replace the empty `handleDown`/`handleUp` stubs with:

```java
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.input.KeyGestureDetectorTest"`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voidterm/input/KeyGestureDetector.java app/src/test/java/com/voidterm/input/KeyGestureDetectorTest.java
git commit -m "feat(input): multi-tap recognition (single/double/triple)"
```

---

## Task 5: Appui long (LONG)

**Files:**
- Modify: `app/src/main/java/com/voidterm/input/KeyGestureDetector.java` (`beginKeyPress` starts a long timer; add `fireLong`)
- Test: `app/src/test/java/com/voidterm/input/KeyGestureDetectorTest.java`

- [ ] **Step 1: Add failing tests**

Append to `KeyGestureDetectorTest`:

```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.input.KeyGestureDetectorTest"`
Expected: FAIL — `longPress_firesAtThreshold` gets 0 events (no long timer yet).

- [ ] **Step 3: Implement long-press**

In `beginKeyPress`, replace the comment `// long-press timer added in Task 5` with:

```java
        if (armedHas(k, Gesture.LONG)) {
            s.longToken = scheduler.postDelayed(() -> fireLong(k), timing.longPressMs);
        }
```

Add the `fireLong` method (next to `endKeyPress`):

```java
    private void fireLong(KeyId k) {
        KeyState s = keyStates.get(k);
        s.longFired = true;
        s.longToken = null;
        s.tapCount = 0;
        listener.onGesture(k, Gesture.LONG);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.input.KeyGestureDetectorTest"`
Expected: PASS (12 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voidterm/input/KeyGestureDetector.java app/src/test/java/com/voidterm/input/KeyGestureDetectorTest.java
git commit -m "feat(input): long-press recognition"
```

---

## Task 6: Combo (Vol+ & Vol−)

When the combo is armed, both volume keys are intercepted. Each volume down opens a short combo window; if the partner volume goes down inside the window, a combo is engaged (with its own single/double/triple counting on release). If the window expires alone, the press is **promoted** to an individual key press (so per-key long/multi-tap still work on that volume).

**Files:**
- Modify: `app/src/main/java/com/voidterm/input/KeyGestureDetector.java` (combo routing + handlers)
- Test: `app/src/test/java/com/voidterm/input/KeyGestureDetectorTest.java`

- [ ] **Step 1: Add failing tests**

Append to `KeyGestureDetectorTest`:

```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.input.KeyGestureDetectorTest"`
Expected: FAIL — combo not implemented; `handleDown` still always calls `beginKeyPress`.

- [ ] **Step 3: Implement combo routing + handlers**

Replace `handleDown` and `handleUp` with:

```java
    private void handleDown(KeyId k) {
        if ((k == KeyId.VOL_UP || k == KeyId.VOL_DOWN) && comboArmed()) {
            handleVolumeDownWithCombo(k);
        } else {
            beginKeyPress(k);
        }
    }

    private void handleUp(KeyId k) {
        KeyState s = keyStates.get(k);
        if ((k == KeyId.VOL_UP || k == KeyId.VOL_DOWN) && comboArmed()
                && (s.comboPending || comboEngaged)) {
            handleVolumeUpWithCombo(k);
        } else {
            endKeyPress(k);
        }
    }
```

Add these combo methods (after `fireLong`):

```java
    private static KeyId otherVolume(KeyId k) {
        return (k == KeyId.VOL_UP) ? KeyId.VOL_DOWN : KeyId.VOL_UP;
    }

    private void handleVolumeDownWithCombo(KeyId k) {
        KeyState s = keyStates.get(k);
        KeyState os = keyStates.get(otherVolume(k));
        s.down = true;
        scheduler.cancel(s.multiTapToken); // continuing a sequence
        s.multiTapToken = null;

        if (os.comboPending) {
            // partner is waiting in its window -> engage combo
            scheduler.cancel(comboWindowToken);
            scheduler.cancel(comboMultiTapToken); // continuing a combo sequence
            comboMultiTapToken = null;
            comboWindowToken = null;
            os.comboPending = false;
            s.comboPending = false;
            comboEngaged = true;
            return;
        }

        // open a combo window for this key
        s.comboPending = true;
        comboWindowToken = scheduler.postDelayed(() -> promoteToIndividual(k), timing.comboWindowMs);
    }

    /** Combo window expired with no partner: treat as an individual key press. */
    private void promoteToIndividual(KeyId k) {
        KeyState s = keyStates.get(k);
        s.comboPending = false;
        comboWindowToken = null;
        if (s.down) {
            if (armedHas(k, Gesture.LONG)) {
                s.longToken = scheduler.postDelayed(() -> fireLong(k), timing.longPressMs);
            }
        } else {
            // released during the window -> it was a tap
            resolveTap(k);
        }
    }

    private void handleVolumeUpWithCombo(KeyId k) {
        KeyState s = keyStates.get(k);
        s.down = false;

        if (s.comboPending) {
            // released before the window expired; promoteToIndividual will resolve it
            return;
        }

        if (comboEngaged) {
            if (!keyStates.get(otherVolume(k)).down) {
                comboEngaged = false;
                onComboTap();
            }
            return;
        }

        // already promoted to an individual press
        endKeyPress(k);
    }

    private void onComboTap() {
        comboTapCount++;
        if (comboTapCount >= maxArmedTaps(KeyId.COMBO)) {
            emitTap(KeyId.COMBO, comboTapCount);
            comboTapCount = 0;
        } else {
            final int snapshot = comboTapCount;
            comboMultiTapToken = scheduler.postDelayed(() -> {
                emitTap(KeyId.COMBO, snapshot);
                comboTapCount = 0;
                comboMultiTapToken = null;
            }, timing.multiTapWindowMs);
        }
    }
```

Refactor the tap-resolution out of `endKeyPress` so the promotion path reuses it. Replace `endKeyPress` with:

```java
    private void endKeyPress(KeyId k) {
        KeyState s = keyStates.get(k);
        s.down = false;
        if (s.longFired) { s.longFired = false; return; }
        scheduler.cancel(s.longToken);
        s.longToken = null;
        resolveTap(k);
    }

    private void resolveTap(KeyId k) {
        KeyState s = keyStates.get(k);
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.input.KeyGestureDetectorTest"`
Expected: PASS (16 tests).

- [ ] **Step 5: Run the whole unit suite (no regressions)**

Run: `./gradlew testDebugUnitTest`
Expected: PASS (all existing tests + the new ones).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/voidterm/input/KeyGestureDetector.java app/src/test/java/com/voidterm/input/KeyGestureDetectorTest.java
git commit -m "feat(input): combo (Vol+ & Vol-) recognition with single-key fallback"
```

---

## Task 7: Constantes de réglages (`SettingsDialog`)

**Files:**
- Modify: `app/src/main/java/com/voidterm/app/SettingsDialog.java`

- [ ] **Step 1: Add the key + preset constants**

In `SettingsDialog`, just after the existing `KEY_VOLUME_*` / `VOLUME_DEFAULT` block (around line 56), add:

```java
    // --- Key gesture slots (multi-tap / long / combo) ---
    // Single-tap reuses the existing keys above; "default" sentinel reuses VOLUME_DEFAULT.
    public static final String KEY_GESTURE_TIMING_PRESET = "gesture_timing_preset";
    public static final String PRESET_FAST = "fast";
    public static final String PRESET_NORMAL = "normal";
    public static final String PRESET_SLOW = "slow";

    public static final String KEY_VOLUP_DOUBLE = "gesture_volup_double";
    public static final String KEY_VOLUP_TRIPLE = "gesture_volup_triple";
    public static final String KEY_VOLUP_LONG = "gesture_volup_long";
    public static final String KEY_VOLUP_DOUBLE_MACRO = "gesture_volup_double_macro";
    public static final String KEY_VOLUP_TRIPLE_MACRO = "gesture_volup_triple_macro";
    public static final String KEY_VOLUP_LONG_MACRO = "gesture_volup_long_macro";

    public static final String KEY_VOLDOWN_DOUBLE = "gesture_voldown_double";
    public static final String KEY_VOLDOWN_TRIPLE = "gesture_voldown_triple";
    public static final String KEY_VOLDOWN_LONG = "gesture_voldown_long";
    public static final String KEY_VOLDOWN_DOUBLE_MACRO = "gesture_voldown_double_macro";
    public static final String KEY_VOLDOWN_TRIPLE_MACRO = "gesture_voldown_triple_macro";
    public static final String KEY_VOLDOWN_LONG_MACRO = "gesture_voldown_long_macro";

    public static final String KEY_BACK_DOUBLE = "gesture_back_double";
    public static final String KEY_BACK_TRIPLE = "gesture_back_triple";
    public static final String KEY_BACK_LONG = "gesture_back_long";
    public static final String KEY_BACK_DOUBLE_MACRO = "gesture_back_double_macro";
    public static final String KEY_BACK_TRIPLE_MACRO = "gesture_back_triple_macro";
    public static final String KEY_BACK_LONG_MACRO = "gesture_back_long_macro";

    public static final String KEY_COMBO_SINGLE = "gesture_combo_single";
    public static final String KEY_COMBO_DOUBLE = "gesture_combo_double";
    public static final String KEY_COMBO_TRIPLE = "gesture_combo_triple";
    public static final String KEY_COMBO_SINGLE_MACRO = "gesture_combo_single_macro";
    public static final String KEY_COMBO_DOUBLE_MACRO = "gesture_combo_double_macro";
    public static final String KEY_COMBO_TRIPLE_MACRO = "gesture_combo_triple_macro";

    public static final String[] GESTURE_PRESET_LABELS = {"Reactif", "Normal", "Tolerant"};
    public static final String[] GESTURE_PRESET_VALUES = {PRESET_FAST, PRESET_NORMAL, PRESET_SLOW};
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL (constants only).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/voidterm/app/SettingsDialog.java
git commit -m "feat(settings): preference key constants for key-gesture slots"
```

---

## Task 8: UI des gestes (`SettingsActivity`)

Add a "Sensibilité" preset spinner and the 12 advanced slots. Introduce a row factory `addGestureRow(...)` and refactor the existing back/volume rows onto it (kills the spinner+macro duplication that exists 3× today). The advanced rows (double/triple/long) sit behind a per-key "Avancé" toggle.

**Files:**
- Modify: `app/src/main/java/com/voidterm/app/SettingsActivity.java`

> Integration UI — tested manually on Quest per project convention (no Robolectric layout tests in this repo).

- [ ] **Step 1: Add the option/value array constants + row factory**

Add these `static final` arrays near the top of `SettingsActivity` (with the other constants). The "with-default" values double as the volume-single options and the noneable advanced options (same values, different label for index 0). Back single tap has **no** default/none entry — its default is Escape, and offering "None" on Back single could leave the back key doing nothing (and closing the app via the legacy path).

```java
    // Shared option sets for key-gesture rows.
    private static final String[] GESTURE_VALUES_WITH_DEFAULT = {
            SettingsDialog.VOLUME_DEFAULT, SettingsDialog.BACK_ESCAPE,
            SettingsDialog.BACK_TOGGLE_KEYBOARD, SettingsDialog.BACK_MACRO,
            SettingsDialog.BACK_VOICE};
    private static final String[] VOLUME_SINGLE_LABELS = {
            "Default (system volume)", "Escape", "Toggle Keyboard", "Macro", "Voice Input"};
    private static final String[] NONEABLE_LABELS = {
            "None", "Escape", "Toggle Keyboard", "Macro", "Voice Input"};
    private static final String[] BACK_SINGLE_LABELS = {
            "Escape", "Toggle Keyboard", "Macro", "Voice Input"};
    private static final String[] BACK_SINGLE_VALUES = {
            SettingsDialog.BACK_ESCAPE, SettingsDialog.BACK_TOGGLE_KEYBOARD,
            SettingsDialog.BACK_MACRO, SettingsDialog.BACK_VOICE};
```

Add this method to `SettingsActivity` (near the other widget factories, after `makeActionButton`). It persists both the behavior (on selection) and the macro (live, via a TextWatcher) — so it fully replaces the old `onPause` macro-saving for these rows:

```java
    /**
     * Adds a labelled behavior spinner + conditional macro field bound to the
     * given preference keys. Shared by every key-gesture slot. The macro field
     * appears only when "Macro" is selected. {@code values}/{@code labels} pick
     * the option set; {@code defaultBehavior} is the pref read fallback (and the
     * preselected value for a fresh install).
     */
    private void addGestureRow(LinearLayout parent, String label,
                               String behaviorKey, String macroKey,
                               String[] labels, String[] values, String defaultBehavior) {
        final int macroIndex = findIndex(values, SettingsDialog.BACK_MACRO);

        parent.addView(makeLabel(label));
        Spinner spinner = makeSpinner(labels);
        String current = prefs.getString(behaviorKey, defaultBehavior);
        spinner.setSelection(findIndex(values, current));
        parent.addView(spinner);

        final EditText macro = new EditText(this);
        macro.setHint("Macro command (e.g. {ctrl+c})");
        macro.setTextColor(textColor);
        macro.setHintTextColor(hintColor);
        macro.setTextSize(14);
        macro.setSingleLine(true);
        macro.setText(prefs.getString(macroKey, ""));
        macro.setVisibility(
                SettingsDialog.BACK_MACRO.equals(current) ? View.VISIBLE : View.GONE);
        parent.addView(macro);

        macro.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(android.text.Editable e) {
                prefs.edit().putString(macroKey, e.toString()).apply();
            }
        });

        spinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putString(behaviorKey, values[pos]).apply();
                macro.setVisibility(pos == macroIndex ? View.VISIBLE : View.GONE);
            }
        });
    }
```

- [ ] **Step 2: Add the `SimpleTextWatcher` helper**

Add next to `SimpleItemSelectedListener` at the bottom of the class:

```java
    /** TextWatcher stub that only needs afterTextChanged. */
    private static abstract class SimpleTextWatcher implements android.text.TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
    }
```

- [ ] **Step 3: Add a per-key "Avancé" expander helper**

Add near `addGestureRow`:

```java
    /** Adds a collapsible "Advanced" sub-group; rows added to the returned container. */
    private LinearLayout addAdvancedGroup(LinearLayout parent) {
        final LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setVisibility(View.GONE);

        final Button toggle = makeActionButton("Advanced...");
        toggle.setOnClickListener(v -> {
            boolean show = container.getVisibility() != View.VISIBLE;
            container.setVisibility(show ? View.VISIBLE : View.GONE);
            toggle.setText(show ? "Advanced ▲" : "Advanced...");
        });
        parent.addView(toggle);
        parent.addView(container);
        return container;
    }
```

- [ ] **Step 4: Rewrite `buildBackKeySection` onto the factory**

Replace the whole `buildBackKeySection()` method with:

```java
    private LinearLayout buildBackKeySection() {
        LinearLayout body = makeSectionBody();

        body.addView(makeSubheading("Back Key"));
        addGestureRow(body, "Single tap",
                SettingsDialog.KEY_BACK_BEHAVIOR, SettingsDialog.KEY_BACK_MACRO,
                BACK_SINGLE_LABELS, BACK_SINGLE_VALUES, SettingsDialog.BACK_ESCAPE);

        LinearLayout adv = addAdvancedGroup(body);
        addGestureRow(adv, "Double tap",
                SettingsDialog.KEY_BACK_DOUBLE, SettingsDialog.KEY_BACK_DOUBLE_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);
        addGestureRow(adv, "Triple tap",
                SettingsDialog.KEY_BACK_TRIPLE, SettingsDialog.KEY_BACK_TRIPLE_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);
        addGestureRow(adv, "Long press",
                SettingsDialog.KEY_BACK_LONG, SettingsDialog.KEY_BACK_LONG_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);

        return body;
    }
```

> The Back single-tap row uses `BACK_SINGLE_LABELS`/`BACK_SINGLE_VALUES` (no "None") and default `BACK_ESCAPE`, so the initial `onItemSelected` (fired by `setSelection`) re-persists `escape` — never `default`. This preserves today's behavior and avoids leaving Back unmapped (which would close the app via the legacy path).

- [ ] **Step 5: Drop the obsolete `onPause` macro saving**

The factory now persists macros live (TextWatcher), so the `onPause()` blocks that saved `macroField` / `volumeUpMacroField` / `volumeDownMacroField` are obsolete and reference fields that will no longer exist. In `onPause()` (around lines 180-204), **keep** the `promptField` save and **remove** the three macro-save `if` blocks:

```java
    @Override
    protected void onPause() {
        super.onPause();
        SharedPreferences.Editor editor = prefs.edit();
        if (promptField != null) {
            editor.putString(SettingsDialog.KEY_WHISPER_INITIAL_PROMPT,
                    promptField.getText().toString());
        }
        editor.apply();
    }
```

(Adjust to match the exact surrounding code; the only change is deleting the three `if (macroField ...)` / `if (volumeUpMacroField ...)` / `if (volumeDownMacroField ...)` blocks.)

- [ ] **Step 6: Rewrite `buildVolumeKeysSection` onto the factory + preset spinner**

Replace the whole `buildVolumeKeysSection()` method with:

```java
    private LinearLayout buildVolumeKeysSection() {
        LinearLayout body = makeSectionBody();

        // Gesture sensitivity preset (applies to all keys)
        body.addView(makeLabel("Gesture sensitivity"));
        Spinner presetSpinner = makeSpinner(SettingsDialog.GESTURE_PRESET_LABELS);
        String currentPreset = prefs.getString(
                SettingsDialog.KEY_GESTURE_TIMING_PRESET, SettingsDialog.PRESET_NORMAL);
        presetSpinner.setSelection(findIndex(SettingsDialog.GESTURE_PRESET_VALUES, currentPreset));
        presetSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putString(SettingsDialog.KEY_GESTURE_TIMING_PRESET,
                        SettingsDialog.GESTURE_PRESET_VALUES[pos]).apply();
            }
        });
        body.addView(presetSpinner);
        body.addView(makeDivider());

        // Volume Up
        body.addView(makeSubheading("Volume Up"));
        addGestureRow(body, "Single tap",
                SettingsDialog.KEY_VOLUME_UP_BEHAVIOR, SettingsDialog.KEY_VOLUME_UP_MACRO,
                VOLUME_SINGLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);
        LinearLayout upAdv = addAdvancedGroup(body);
        addGestureRow(upAdv, "Double tap",
                SettingsDialog.KEY_VOLUP_DOUBLE, SettingsDialog.KEY_VOLUP_DOUBLE_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);
        addGestureRow(upAdv, "Triple tap",
                SettingsDialog.KEY_VOLUP_TRIPLE, SettingsDialog.KEY_VOLUP_TRIPLE_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);
        addGestureRow(upAdv, "Long press",
                SettingsDialog.KEY_VOLUP_LONG, SettingsDialog.KEY_VOLUP_LONG_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);

        body.addView(makeDivider());

        // Volume Down
        body.addView(makeSubheading("Volume Down"));
        addGestureRow(body, "Single tap",
                SettingsDialog.KEY_VOLUME_DOWN_BEHAVIOR, SettingsDialog.KEY_VOLUME_DOWN_MACRO,
                VOLUME_SINGLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);
        LinearLayout downAdv = addAdvancedGroup(body);
        addGestureRow(downAdv, "Double tap",
                SettingsDialog.KEY_VOLDOWN_DOUBLE, SettingsDialog.KEY_VOLDOWN_DOUBLE_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);
        addGestureRow(downAdv, "Triple tap",
                SettingsDialog.KEY_VOLDOWN_TRIPLE, SettingsDialog.KEY_VOLDOWN_TRIPLE_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);
        addGestureRow(downAdv, "Long press",
                SettingsDialog.KEY_VOLDOWN_LONG, SettingsDialog.KEY_VOLDOWN_LONG_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);

        body.addView(makeDivider());

        // Combo (Vol+ & Vol-)
        body.addView(makeSubheading("Combo (Vol+ & Vol-)"));
        addGestureRow(body, "Single",
                SettingsDialog.KEY_COMBO_SINGLE, SettingsDialog.KEY_COMBO_SINGLE_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);
        addGestureRow(body, "Double",
                SettingsDialog.KEY_COMBO_DOUBLE, SettingsDialog.KEY_COMBO_DOUBLE_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);
        addGestureRow(body, "Triple",
                SettingsDialog.KEY_COMBO_TRIPLE, SettingsDialog.KEY_COMBO_TRIPLE_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);

        return body;
    }
```

- [ ] **Step 7: Delete the three obsolete field declarations**

Delete these now-unused class fields (around lines 76-78):

```java
    private EditText macroField;
    private EditText volumeUpMacroField;
    private EditText volumeDownMacroField;
```

Verify nothing else references them:

Run: `rg -n "macroField|volumeUpMacroField|volumeDownMacroField" app/src/main/java/com/voidterm/app/SettingsActivity.java`
Expected: no matches.

- [ ] **Step 8: Compile**

Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/voidterm/app/SettingsActivity.java
git commit -m "feat(settings): key-gesture UI (sensitivity preset + 12 slots, shared row factory)"
```

---

## Task 9: Refactor du dispatch + émulation volume (`TermuxActivity`)

Extract the duplicated behavior switch into `dispatchKeyAction(...)`, add `adjustVolume(...)`. This task must not change observable behavior of the existing back/volume single taps.

**Files:**
- Modify: `app/src/main/java/com/voidterm/app/TermuxActivity.java`

> Integration — verified by build + manual smoke test (project convention).

- [ ] **Step 1: Add `dispatchKeyAction` and `adjustVolume`**

Add these methods to `TermuxActivity` (right after `handleCustomVolumeKey`, ending ~line 724):

```java
    /**
     * Executes a behavior shared by the back/volume keys and the gesture slots.
     * Returns true if it acted; false if {@code behavior} is the "default"
     * sentinel (the caller decides what default means in its context).
     */
    private boolean dispatchKeyAction(String behavior, String macroPrefKey) {
        if (SettingsDialog.BACK_ESCAPE.equals(behavior)) {
            TerminalSession current = getCurrentSession();
            if (current != null) current.write("\033");
            return true;
        }
        if (SettingsDialog.BACK_TOGGLE_KEYBOARD.equals(behavior)) {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0);
            return true;
        }
        if (SettingsDialog.BACK_MACRO.equals(behavior)) {
            SharedPreferences prefs = getSharedPreferences(SettingsDialog.PREFS_NAME, MODE_PRIVATE);
            String macro = prefs.getString(macroPrefKey, "");
            TerminalSession current = getCurrentSession();
            if (!macro.isEmpty() && current != null) {
                MacroExecutor.execute(macro, current::write,
                        terminalView != null ? terminalView.getHandler() : null);
            }
            return true;
        }
        if (SettingsDialog.BACK_VOICE.equals(behavior)) {
            onVoiceToggle();
            return true;
        }
        return false; // default / unknown
    }

    private void adjustVolume(boolean raise) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    raise ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI);
        }
    }
```

Add the import at the top of the file (with the other `android.media` / `android.content` imports):

```java
import android.media.AudioManager;
```

- [ ] **Step 2: Route `handleCustomBackKey` through `dispatchKeyAction`**

Replace the body of `handleCustomBackKey()` with:

```java
    private boolean handleCustomBackKey() {
        SharedPreferences prefs = getSharedPreferences(SettingsDialog.PREFS_NAME, MODE_PRIVATE);
        String behavior = prefs.getString(SettingsDialog.KEY_BACK_BEHAVIOR, SettingsDialog.BACK_ESCAPE);

        // Escape on the back key keeps delegating to TerminalView (unchanged).
        if (SettingsDialog.BACK_ESCAPE.equals(behavior)) {
            return false;
        }
        return dispatchKeyAction(behavior, SettingsDialog.KEY_BACK_MACRO);
    }
```

- [ ] **Step 3: Route `handleCustomVolumeKey` through `dispatchKeyAction`**

Replace the body of `handleCustomVolumeKey(int keyCode)` with:

```java
    private boolean handleCustomVolumeKey(int keyCode) {
        SharedPreferences prefs = getSharedPreferences(SettingsDialog.PREFS_NAME, MODE_PRIVATE);
        boolean up = keyCode == KeyEvent.KEYCODE_VOLUME_UP;
        String behaviorKey = up
                ? SettingsDialog.KEY_VOLUME_UP_BEHAVIOR : SettingsDialog.KEY_VOLUME_DOWN_BEHAVIOR;
        String behavior = prefs.getString(behaviorKey, SettingsDialog.VOLUME_DEFAULT);

        if (SettingsDialog.VOLUME_DEFAULT.equals(behavior)) {
            return false; // let the system handle volume
        }
        String macroKey = up
                ? SettingsDialog.KEY_VOLUME_UP_MACRO : SettingsDialog.KEY_VOLUME_DOWN_MACRO;
        return dispatchKeyAction(behavior, macroKey);
    }
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Manual smoke test (build + install)**

Run:
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Verify (no gestures configured yet): Volume keys still control system volume; if a volume single-tap was set to Escape, it still sends Escape; Back still behaves as before. Behavior must be identical to pre-refactor.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/voidterm/app/TermuxActivity.java
git commit -m "refactor(input): extract dispatchKeyAction + add volume emulation helper"
```

---

## Task 10: Câblage du détecteur (`TermuxActivity`)

Instantiate the detector, build its armed-set + timing from prefs, feed it key events, dispatch resolved gestures, and manage lifecycle (reload on resume, reset on pause).

**Files:**
- Modify: `app/src/main/java/com/voidterm/app/TermuxActivity.java`

> Integration — verified by build + manual on-device test.

- [ ] **Step 1: Add imports + the detector field**

Add the following imports at the top of the file **only if not already present** (TermuxActivity is large — check first with `rg -n "^import android.os.Handler;|^import java.util.Map;" app/src/main/java/com/voidterm/app/TermuxActivity.java`; a duplicate single-type import is a compile error):

```java
import android.os.Handler;
import android.os.Looper;

import com.voidterm.input.GestureTiming;
import com.voidterm.input.HandlerScheduler;
import com.voidterm.input.KeyGestureDetector;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
```

Add a field next to the other input fields (e.g. near `questInputHandler`):

```java
    private KeyGestureDetector gestureDetector;
```

- [ ] **Step 2: Construct the detector in `onCreate`**

Where `questInputHandler` is constructed in `onCreate`, add right after it:

```java
        gestureDetector = new KeyGestureDetector(
                new HandlerScheduler(new Handler(Looper.getMainLooper())),
                this::onGestureResolved);
        refreshGestureConfig();
```

- [ ] **Step 3: Add the config loader + pref-key mapping**

Add these methods to `TermuxActivity`:

```java
    /** Rebuilds the detector's armed-set + timing from SharedPreferences. */
    private void refreshGestureConfig() {
        if (gestureDetector == null) return;
        SharedPreferences prefs = getSharedPreferences(SettingsDialog.PREFS_NAME, MODE_PRIVATE);

        Map<KeyGestureDetector.KeyId, EnumSet<KeyGestureDetector.Gesture>> armed =
                new EnumMap<>(KeyGestureDetector.KeyId.class);
        armed.put(KeyGestureDetector.KeyId.VOL_UP, armedFor(prefs,
                SettingsDialog.KEY_VOLUP_DOUBLE, SettingsDialog.KEY_VOLUP_TRIPLE, SettingsDialog.KEY_VOLUP_LONG));
        armed.put(KeyGestureDetector.KeyId.VOL_DOWN, armedFor(prefs,
                SettingsDialog.KEY_VOLDOWN_DOUBLE, SettingsDialog.KEY_VOLDOWN_TRIPLE, SettingsDialog.KEY_VOLDOWN_LONG));
        armed.put(KeyGestureDetector.KeyId.BACK, armedFor(prefs,
                SettingsDialog.KEY_BACK_DOUBLE, SettingsDialog.KEY_BACK_TRIPLE, SettingsDialog.KEY_BACK_LONG));
        armed.put(KeyGestureDetector.KeyId.COMBO, comboArmedFor(prefs));
        gestureDetector.setArmed(armed);

        gestureDetector.setTiming(GestureTiming.fromPreset(
                prefs.getString(SettingsDialog.KEY_GESTURE_TIMING_PRESET, SettingsDialog.PRESET_NORMAL)));
    }

    private static boolean isConfigured(SharedPreferences prefs, String key) {
        return !SettingsDialog.VOLUME_DEFAULT.equals(
                prefs.getString(key, SettingsDialog.VOLUME_DEFAULT));
    }

    private static EnumSet<KeyGestureDetector.Gesture> armedFor(SharedPreferences prefs,
            String doubleKey, String tripleKey, String longKey) {
        EnumSet<KeyGestureDetector.Gesture> set =
                EnumSet.noneOf(KeyGestureDetector.Gesture.class);
        if (isConfigured(prefs, doubleKey)) set.add(KeyGestureDetector.Gesture.DOUBLE);
        if (isConfigured(prefs, tripleKey)) set.add(KeyGestureDetector.Gesture.TRIPLE);
        if (isConfigured(prefs, longKey)) set.add(KeyGestureDetector.Gesture.LONG);
        return set;
    }

    private static EnumSet<KeyGestureDetector.Gesture> comboArmedFor(SharedPreferences prefs) {
        EnumSet<KeyGestureDetector.Gesture> set =
                EnumSet.noneOf(KeyGestureDetector.Gesture.class);
        if (isConfigured(prefs, SettingsDialog.KEY_COMBO_SINGLE)) set.add(KeyGestureDetector.Gesture.SINGLE);
        if (isConfigured(prefs, SettingsDialog.KEY_COMBO_DOUBLE)) set.add(KeyGestureDetector.Gesture.DOUBLE);
        if (isConfigured(prefs, SettingsDialog.KEY_COMBO_TRIPLE)) set.add(KeyGestureDetector.Gesture.TRIPLE);
        return set;
    }

    /** Maps a resolved (key, gesture) to the behavior + macro preference keys. */
    private static String behaviorPrefKey(KeyGestureDetector.KeyId key, KeyGestureDetector.Gesture g) {
        switch (key) {
            case VOL_UP:
                if (g == KeyGestureDetector.Gesture.SINGLE) return SettingsDialog.KEY_VOLUME_UP_BEHAVIOR;
                if (g == KeyGestureDetector.Gesture.DOUBLE) return SettingsDialog.KEY_VOLUP_DOUBLE;
                if (g == KeyGestureDetector.Gesture.TRIPLE) return SettingsDialog.KEY_VOLUP_TRIPLE;
                return SettingsDialog.KEY_VOLUP_LONG;
            case VOL_DOWN:
                if (g == KeyGestureDetector.Gesture.SINGLE) return SettingsDialog.KEY_VOLUME_DOWN_BEHAVIOR;
                if (g == KeyGestureDetector.Gesture.DOUBLE) return SettingsDialog.KEY_VOLDOWN_DOUBLE;
                if (g == KeyGestureDetector.Gesture.TRIPLE) return SettingsDialog.KEY_VOLDOWN_TRIPLE;
                return SettingsDialog.KEY_VOLDOWN_LONG;
            case BACK:
                if (g == KeyGestureDetector.Gesture.SINGLE) return SettingsDialog.KEY_BACK_BEHAVIOR;
                if (g == KeyGestureDetector.Gesture.DOUBLE) return SettingsDialog.KEY_BACK_DOUBLE;
                if (g == KeyGestureDetector.Gesture.TRIPLE) return SettingsDialog.KEY_BACK_TRIPLE;
                return SettingsDialog.KEY_BACK_LONG;
            case COMBO:
            default:
                if (g == KeyGestureDetector.Gesture.DOUBLE) return SettingsDialog.KEY_COMBO_DOUBLE;
                if (g == KeyGestureDetector.Gesture.TRIPLE) return SettingsDialog.KEY_COMBO_TRIPLE;
                return SettingsDialog.KEY_COMBO_SINGLE;
        }
    }

    private static String macroPrefKey(KeyGestureDetector.KeyId key, KeyGestureDetector.Gesture g) {
        switch (key) {
            case VOL_UP:
                if (g == KeyGestureDetector.Gesture.SINGLE) return SettingsDialog.KEY_VOLUME_UP_MACRO;
                if (g == KeyGestureDetector.Gesture.DOUBLE) return SettingsDialog.KEY_VOLUP_DOUBLE_MACRO;
                if (g == KeyGestureDetector.Gesture.TRIPLE) return SettingsDialog.KEY_VOLUP_TRIPLE_MACRO;
                return SettingsDialog.KEY_VOLUP_LONG_MACRO;
            case VOL_DOWN:
                if (g == KeyGestureDetector.Gesture.SINGLE) return SettingsDialog.KEY_VOLUME_DOWN_MACRO;
                if (g == KeyGestureDetector.Gesture.DOUBLE) return SettingsDialog.KEY_VOLDOWN_DOUBLE_MACRO;
                if (g == KeyGestureDetector.Gesture.TRIPLE) return SettingsDialog.KEY_VOLDOWN_TRIPLE_MACRO;
                return SettingsDialog.KEY_VOLDOWN_LONG_MACRO;
            case BACK:
                if (g == KeyGestureDetector.Gesture.SINGLE) return SettingsDialog.KEY_BACK_MACRO;
                if (g == KeyGestureDetector.Gesture.DOUBLE) return SettingsDialog.KEY_BACK_DOUBLE_MACRO;
                if (g == KeyGestureDetector.Gesture.TRIPLE) return SettingsDialog.KEY_BACK_TRIPLE_MACRO;
                return SettingsDialog.KEY_BACK_LONG_MACRO;
            case COMBO:
            default:
                if (g == KeyGestureDetector.Gesture.DOUBLE) return SettingsDialog.KEY_COMBO_DOUBLE_MACRO;
                if (g == KeyGestureDetector.Gesture.TRIPLE) return SettingsDialog.KEY_COMBO_TRIPLE_MACRO;
                return SettingsDialog.KEY_COMBO_SINGLE_MACRO;
        }
    }

    /** Called on the main thread by the gesture detector. */
    private void onGestureResolved(KeyGestureDetector.KeyId key, KeyGestureDetector.Gesture gesture) {
        SharedPreferences prefs = getSharedPreferences(SettingsDialog.PREFS_NAME, MODE_PRIVATE);
        String behaviorKey = behaviorPrefKey(key, gesture);
        // Single tap of Back defaults to Escape; everything else defaults to "none".
        String defaultBehavior = (key == KeyGestureDetector.KeyId.BACK
                && gesture == KeyGestureDetector.Gesture.SINGLE)
                ? SettingsDialog.BACK_ESCAPE : SettingsDialog.VOLUME_DEFAULT;
        String behavior = prefs.getString(behaviorKey, defaultBehavior);

        boolean handled = dispatchKeyAction(behavior, macroPrefKey(key, gesture));
        if (!handled) {
            // behavior == default: emulate system volume for the volume keys
            if (key == KeyGestureDetector.KeyId.VOL_UP) adjustVolume(true);
            else if (key == KeyGestureDetector.KeyId.VOL_DOWN) adjustVolume(false);
            // BACK / COMBO default -> nothing
        }
    }
```

- [ ] **Step 4: Feed the detector in `onKeyDown` / `onKeyUp`**

In `onKeyDown`, after the `questInputHandler` block and before the existing `handleCustomBackKey` / `handleCustomVolumeKey` block, insert:

```java
        if (gestureDetector != null && gestureDetector.onKeyDown(keyCode, event)) {
            return true;
        }
```

So the method reads:

```java
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (questInputHandler != null && questInputHandler.onKeyDown(keyCode, event)) {
            return true;
        }
        if (gestureDetector != null && gestureDetector.onKeyDown(keyCode, event)) {
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && handleCustomBackKey()) {
            return true;
        }
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                && handleCustomVolumeKey(keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
```

In `onKeyUp`, after the `questInputHandler` block, insert:

```java
        if (gestureDetector != null && gestureDetector.onKeyUp(keyCode, event)) {
            return true;
        }
```

So `onKeyUp` reads:

```java
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (questInputHandler != null && questInputHandler.onKeyUp(keyCode, event)) {
            return true;
        }
        if (gestureDetector != null && gestureDetector.onKeyUp(keyCode, event)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
```

- [ ] **Step 5: Reload on resume, reset on pause**

In `onResume()`, add (after the existing theme-sync logic):

```java
        refreshGestureConfig();
```

In `onPause()`, add (next to the existing voice-cancel logic):

```java
        if (gestureDetector != null) gestureDetector.reset();
```

- [ ] **Step 6: Compile**

Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Full build + unit suite**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 8: Manual on-device test (Quest)**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Checklist:
- No gestures configured → volume keys = system volume, Back = Escape (unchanged).
- Set Vol+ double tap = Macro `{ctrl+c}` → single Vol+ still raises volume (after a short delay), double Vol+ sends Ctrl-C.
- Set Vol− long press = Voice Input → holding Vol− ~0.5s starts recording; quick tap still lowers volume.
- Set Back double tap = Toggle Keyboard → double Back toggles keyboard; single Back still sends Escape; **app never closes**.
- Set Combo single = Escape → pressing both volume keys together sends Escape.
- Switch sensitivity to "Tolerant" → multi-taps become easier (longer window).
- Background the app mid-press → returning, no stuck/phantom gesture.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/voidterm/app/TermuxActivity.java
git commit -m "feat(input): wire KeyGestureDetector into TermuxActivity (gestures + lifecycle)"
```

---

## Notes d'implémentation

- **Ordre d'interception dans `onKeyDown`** : `QuestInputHandler` → `KeyGestureDetector` → legacy `handleCustom*`. Le détecteur ne consomme que les touches *armées* ; sinon il rend `false` et le chemin legacy (instantané) s'exécute. Une touche au simple tap seul n'est donc jamais ralentie.
- **Sécurité Back** : dès qu'un geste est armé sur Back, le détecteur consomme **tous** les events Back (`true`), donc aucun event ne remonte vers `super` (pas de fermeture d'app). Le simple tap Back émet alors `SINGLE` → `dispatchKeyAction` → `\033` (défaut Escape).
- **Émulation volume (option B)** : si le simple tap d'un volume intercepté reste sur `default`, `onGestureResolved` appelle `adjustVolume()` → volume système préservé.
- **PTT** : `QuestInputHandler` ne consomme que son keycode PTT (bouton manette). Si l'utilisateur configurait le PTT sur une touche volume, il primerait sur le détecteur — comportement existant, hors périmètre.
