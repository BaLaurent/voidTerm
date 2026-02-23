package com.voidterm.app;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link MacroExecutor} parse() and resolveTag() methods.
 *
 * These are package-private methods accessed directly since the test
 * shares the same package as the source.
 */
public class MacroExecutorTest {

    // ---------------------------------------------------------------
    // parse() tests
    // ---------------------------------------------------------------

    @Test
    public void parse_plainTextWithoutBraces_returnsSingleStep() {
        List<MacroExecutor.MacroStep> steps = MacroExecutor.parse("hello world");
        assertEquals(1, steps.size());
        assertEquals("hello world", steps.get(0).text);
        assertEquals(0, steps.get(0).delayMs);
    }

    @Test
    public void parse_singleEscTag_returnsSingleEscapeStep() {
        List<MacroExecutor.MacroStep> steps = MacroExecutor.parse("{esc}");
        assertEquals(1, steps.size());
        assertEquals("\033", steps.get(0).text);
    }

    @Test
    public void parse_multipleTags_returnsMultipleSteps() {
        List<MacroExecutor.MacroStep> steps = MacroExecutor.parse("{up}{down}");
        assertEquals(2, steps.size());
        assertEquals("\033[A", steps.get(0).text);
        assertEquals("\033[B", steps.get(1).text);
    }

    @Test
    public void parse_mixedTextAndTags_returnsSeparateSteps() {
        List<MacroExecutor.MacroStep> steps = MacroExecutor.parse("hello{enter}");
        assertEquals(2, steps.size());
        assertEquals("hello", steps.get(0).text);
        assertEquals("\r", steps.get(1).text);
    }

    @Test
    public void parse_escapedOpenBrace_returnsLiteralBrace() {
        List<MacroExecutor.MacroStep> steps = MacroExecutor.parse("{{");
        assertEquals(1, steps.size());
        assertEquals("{", steps.get(0).text);
    }

    @Test
    public void parse_escapedCloseBrace_returnsLiteralBrace() {
        List<MacroExecutor.MacroStep> steps = MacroExecutor.parse("}}");
        assertEquals(1, steps.size());
        assertEquals("}", steps.get(0).text);
    }

    @Test
    public void parse_escapedBracesMixedWithTags_correctlyParsed() {
        List<MacroExecutor.MacroStep> steps = MacroExecutor.parse("{{text}}{enter}");
        assertEquals(2, steps.size());
        assertEquals("{text}", steps.get(0).text);
        assertEquals("\r", steps.get(1).text);
    }

    @Test
    public void parse_waitTag_returnsDelayStep() {
        List<MacroExecutor.MacroStep> steps = MacroExecutor.parse("{wait:500}");
        assertEquals(1, steps.size());
        assertNull(steps.get(0).text);
        assertEquals(500, steps.get(0).delayMs);
    }

    @Test
    public void parse_waitZero_returnsDelayStepWithZeroMs() {
        List<MacroExecutor.MacroStep> steps = MacroExecutor.parse("{wait:0}");
        assertEquals(1, steps.size());
        assertNull(steps.get(0).text);
        assertEquals(0, steps.get(0).delayMs);
    }

    @Test
    public void parse_waitExceedsMax_clampedTo5000() {
        List<MacroExecutor.MacroStep> steps = MacroExecutor.parse("{wait:10000}");
        assertEquals(1, steps.size());
        assertNull(steps.get(0).text);
        assertEquals(5000, steps.get(0).delayMs);
    }

    @Test
    public void parse_waitNegative_clampedToZero() {
        List<MacroExecutor.MacroStep> steps = MacroExecutor.parse("{wait:-5}");
        assertEquals(1, steps.size());
        assertNull(steps.get(0).text);
        assertEquals(0, steps.get(0).delayMs);
    }

    @Test
    public void parse_unknownTag_returnedAsLiteralText() {
        List<MacroExecutor.MacroStep> steps = MacroExecutor.parse("{blah}");
        assertEquals(1, steps.size());
        assertEquals("{blah}", steps.get(0).text);
    }

    @Test
    public void parse_unclosedBrace_returnedAsLiteralText() {
        List<MacroExecutor.MacroStep> steps = MacroExecutor.parse("{hello");
        assertEquals(1, steps.size());
        assertEquals("{hello", steps.get(0).text);
    }

    @Test
    public void parse_emptyString_returnsEmptyList() {
        List<MacroExecutor.MacroStep> steps = MacroExecutor.parse("");
        assertTrue(steps.isEmpty());
    }

    @Test
    public void parse_uppercaseTag_treatedAsCaseInsensitive() {
        List<MacroExecutor.MacroStep> steps = MacroExecutor.parse("{ESC}");
        assertEquals(1, steps.size());
        assertEquals("\033", steps.get(0).text);
    }

    // ---------------------------------------------------------------
    // resolveTag() — simple key tests
    // ---------------------------------------------------------------

    @Test
    public void resolveTag_esc_returnsEscapeChar() {
        assertEquals("\033", MacroExecutor.resolveTag("esc"));
    }

    @Test
    public void resolveTag_enter_returnsCarriageReturn() {
        assertEquals("\r", MacroExecutor.resolveTag("enter"));
    }

    @Test
    public void resolveTag_tab_returnsTab() {
        assertEquals("\t", MacroExecutor.resolveTag("tab"));
    }

    @Test
    public void resolveTag_space_returnsSpace() {
        assertEquals(" ", MacroExecutor.resolveTag("space"));
    }

    @Test
    public void resolveTag_bksp_returnsDeleteChar() {
        assertEquals("\u007F", MacroExecutor.resolveTag("bksp"));
    }

    @Test
    public void resolveTag_del_returnsDeleteSequence() {
        assertEquals("\033[3~", MacroExecutor.resolveTag("del"));
    }

    @Test
    public void resolveTag_up_returnsUpArrow() {
        assertEquals("\033[A", MacroExecutor.resolveTag("up"));
    }

    @Test
    public void resolveTag_down_returnsDownArrow() {
        assertEquals("\033[B", MacroExecutor.resolveTag("down"));
    }

    @Test
    public void resolveTag_left_returnsLeftArrow() {
        assertEquals("\033[D", MacroExecutor.resolveTag("left"));
    }

    @Test
    public void resolveTag_right_returnsRightArrow() {
        assertEquals("\033[C", MacroExecutor.resolveTag("right"));
    }

    @Test
    public void resolveTag_home_returnsHomeSequence() {
        assertEquals("\033[H", MacroExecutor.resolveTag("home"));
    }

    @Test
    public void resolveTag_end_returnsEndSequence() {
        assertEquals("\033[F", MacroExecutor.resolveTag("end"));
    }

    @Test
    public void resolveTag_unknown_returnsNull() {
        assertNull(MacroExecutor.resolveTag("unknown"));
    }

    // ---------------------------------------------------------------
    // resolveTag() — combo key tests (delegating to resolveCombo)
    // ---------------------------------------------------------------

    @Test
    public void resolveTag_ctrlA_returnsControlCharOne() {
        assertEquals("\u0001", MacroExecutor.resolveTag("ctrl+a"));
    }

    @Test
    public void resolveTag_ctrlZ_returnsControlChar26() {
        assertEquals("\u001A", MacroExecutor.resolveTag("ctrl+z"));
    }

    @Test
    public void resolveTag_altX_returnsEscPrefixedChar() {
        assertEquals("\033x", MacroExecutor.resolveTag("alt+x"));
    }

    @Test
    public void resolveTag_ctrlAltC_returnsEscPrefixedControlChar() {
        assertEquals("\033\u0003", MacroExecutor.resolveTag("ctrl+alt+c"));
    }

    @Test
    public void resolveTag_shiftUp_returnsModifiedArrowSequence() {
        // shift = modifier 2, up is 3-char sequence \033[A
        assertEquals("\033[1;2A", MacroExecutor.resolveTag("shift+up"));
    }

    @Test
    public void resolveTag_ctrlRight_returnsModifiedArrowSequence() {
        // ctrl = modifier 5, right is 3-char sequence \033[C
        assertEquals("\033[1;5C", MacroExecutor.resolveTag("ctrl+right"));
    }

    @Test
    public void resolveTag_ctrlShiftF5_returnsModifiedFunctionKey() {
        // ctrl+shift = modifier 6, f5 is \033[15~ (tilde-terminated)
        assertEquals("\033[15;6~", MacroExecutor.resolveTag("ctrl+shift+f5"));
    }

    @Test
    public void resolveTag_shiftPgdn_returnsModifiedPageDown() {
        // shift = modifier 2, pgdn is \033[6~ (tilde-terminated)
        assertEquals("\033[6;2~", MacroExecutor.resolveTag("shift+pgdn"));
    }
}
