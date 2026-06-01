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
            long step = next.remaining;
            decrementActive(step);
            left -= step;
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
