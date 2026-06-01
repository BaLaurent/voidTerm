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
