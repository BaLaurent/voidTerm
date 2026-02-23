package com.voidterm.app;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link CompactPanel} modifier and macro page state.
 *
 * CompactPanel has no shift button (dedicated S-TAB/S-ENT instead)
 * and shows all 12 macros at once (no paging).
 */
@RunWith(RobolectricTestRunner.class)
public class CompactPanelTest {

    private CompactPanel panel;

    @Before
    public void setUp() {
        panel = new CompactPanel(RuntimeEnvironment.getApplication());
    }

    // ---------------------------------------------------------------
    // Shift is always off (no shift button)
    // ---------------------------------------------------------------

    @Test
    public void isShiftActive_alwaysFalse() {
        assertFalse(panel.isShiftActive());
    }

    @Test
    public void setShiftActive_doesNothing() {
        panel.setShiftActive(true);
        assertFalse(panel.isShiftActive());
    }

    @Test
    public void resetShift_doesNothing() {
        panel.resetShift();
        assertFalse(panel.isShiftActive());
    }

    // ---------------------------------------------------------------
    // Ctrl toggle
    // ---------------------------------------------------------------

    @Test
    public void ctrl_defaultsToOff() {
        assertFalse(panel.isCtrlActive());
    }

    @Test
    public void setCtrlActive_true() {
        panel.setCtrlActive(true);
        assertTrue(panel.isCtrlActive());
    }

    @Test
    public void setCtrlActive_false() {
        panel.setCtrlActive(true);
        panel.setCtrlActive(false);
        assertFalse(panel.isCtrlActive());
    }

    @Test
    public void resetCtrl_clearsActiveState() {
        panel.setCtrlActive(true);
        panel.resetCtrl();
        assertFalse(panel.isCtrlActive());
    }

    // ---------------------------------------------------------------
    // Macro page (all visible, no paging)
    // ---------------------------------------------------------------

    @Test
    public void getCurrentMacroPage_alwaysZero() {
        assertEquals(0, panel.getCurrentMacroPage());
    }

    @Test
    public void setCurrentMacroPage_doesNotChangePage() {
        panel.setCurrentMacroPage(2);
        assertEquals(0, panel.getCurrentMacroPage());
    }
}
