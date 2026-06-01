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
