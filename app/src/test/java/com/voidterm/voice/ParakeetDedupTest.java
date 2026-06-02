package com.voidterm.voice;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link ParakeetEngine#dedupLeading} — the word-level overlap trim
 * applied to continuous-speech (fallback) chunk transcripts.
 */
public class ParakeetDedupTest {

    @Test
    public void singleWordOverlap_trimmed() {
        assertEquals("jumps over", ParakeetEngine.dedupLeading("the quick brown fox", "fox jumps over"));
    }

    @Test
    public void multiWordOverlap_trimmed() {
        assertEquals("baz qux", ParakeetEngine.dedupLeading("hello world foo bar", "foo bar baz qux"));
    }

    @Test
    public void caseInsensitiveOverlap_trimmed() {
        assertEquals("World", ParakeetEngine.dedupLeading("say Hello", "hello World"));
    }

    @Test
    public void noOverlap_unchanged() {
        assertEquals("def ghi", ParakeetEngine.dedupLeading("abc", "def ghi"));
    }

    @Test
    public void emptyPrev_unchanged() {
        assertEquals("abc", ParakeetEngine.dedupLeading("", "abc"));
    }

    @Test
    public void emptyCur_unchanged() {
        assertEquals("", ParakeetEngine.dedupLeading("abc", ""));
    }
}
