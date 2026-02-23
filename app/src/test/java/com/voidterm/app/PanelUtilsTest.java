package com.voidterm.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for {@link PanelUtils#descriptionForLabel(String)}.
 *
 * Tests the pure-logic accessibility description mapping. Each Unicode label
 * used on panel buttons maps to a human-readable content description.
 */
public class PanelUtilsTest {

    // ---------------------------------------------------------------
    // descriptionForLabel() — mapped labels
    // ---------------------------------------------------------------

    @Test
    public void upArrow_returnsUpArrowDescription() {
        assertEquals("Up arrow", PanelUtils.descriptionForLabel("\u25B2"));
    }

    @Test
    public void downArrow_returnsDownArrowDescription() {
        assertEquals("Down arrow", PanelUtils.descriptionForLabel("\u25BC"));
    }

    @Test
    public void leftArrow_returnsLeftArrowDescription() {
        assertEquals("Left arrow", PanelUtils.descriptionForLabel("\u25C0"));
    }

    @Test
    public void rightArrow_returnsRightArrowDescription() {
        assertEquals("Right arrow", PanelUtils.descriptionForLabel("\u25B6"));
    }

    @Test
    public void enterSymbol_returnsEnterDescription() {
        assertEquals("Enter", PanelUtils.descriptionForLabel("\u21B5"));
    }

    @Test
    public void microphoneEmoji_returnsVoiceInputDescription() {
        assertEquals("Voice input", PanelUtils.descriptionForLabel("\uD83C\uDFA4"));
    }

    @Test
    public void hamburgerMenu_returnsMenuDescription() {
        assertEquals("Menu", PanelUtils.descriptionForLabel("\u2630"));
    }

    @Test
    public void ctl_returnsControlModifierDescription() {
        assertEquals("Control modifier", PanelUtils.descriptionForLabel("CTL"));
    }

    @Test
    public void shf_returnsShiftModifierDescription() {
        assertEquals("Shift modifier", PanelUtils.descriptionForLabel("SHF"));
    }

    @Test
    public void esc_returnsEscapeDescription() {
        assertEquals("Escape", PanelUtils.descriptionForLabel("ESC"));
    }

    @Test
    public void tab_returnsTabDescription() {
        assertEquals("Tab", PanelUtils.descriptionForLabel("TAB"));
    }

    @Test
    public void shiftTab_returnsShiftTabDescription() {
        assertEquals("Shift Tab", PanelUtils.descriptionForLabel("S-TAB"));
    }

    @Test
    public void shiftEnter_returnsShiftEnterDescription() {
        assertEquals("Shift Enter", PanelUtils.descriptionForLabel("S-\u21B5"));
    }

    // ---------------------------------------------------------------
    // descriptionForLabel() — unmapped labels (default passthrough)
    // ---------------------------------------------------------------

    @Test
    public void unknownLabel_returnsSelf() {
        assertEquals("M1", PanelUtils.descriptionForLabel("M1"));
    }

    @Test
    public void emptyString_returnsSelf() {
        assertEquals("", PanelUtils.descriptionForLabel(""));
    }
}
