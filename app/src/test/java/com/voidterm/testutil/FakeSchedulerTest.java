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
