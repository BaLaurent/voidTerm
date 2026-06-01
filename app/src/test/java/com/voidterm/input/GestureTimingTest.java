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
