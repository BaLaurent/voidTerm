package com.voidterm.voice;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WhisperModelCatalogTest {

    @Test
    public void hasAllThirtyOneVariants() {
        assertEquals(31, WhisperModelCatalog.ALL.size());
    }

    @Test
    public void idsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (WhisperModelCatalog.WhisperModel m : WhisperModelCatalog.ALL) {
            assertTrue("duplicate id: " + m.id, ids.add(m.id));
        }
    }

    @Test
    public void fileNamesStartWithGgmlAndUrlMatches() {
        for (WhisperModelCatalog.WhisperModel m : WhisperModelCatalog.ALL) {
            assertTrue(m.fileName.startsWith("ggml-"));
            assertTrue(m.fileName.endsWith(".bin"));
            assertEquals(WhisperModelCatalog.BASE_URL + m.fileName, m.url());
            assertTrue("size must be positive: " + m.id, m.sizeMb > 0);
        }
    }

    @Test
    public void familiesAreKnown() {
        Set<String> known = new HashSet<>();
        known.add("tiny"); known.add("base"); known.add("small");
        known.add("medium"); known.add("large");
        for (WhisperModelCatalog.WhisperModel m : WhisperModelCatalog.ALL) {
            assertTrue("unknown family: " + m.family, known.contains(m.family));
        }
    }

    @Test
    public void englishOnlyFlagMatchesFileName() {
        for (WhisperModelCatalog.WhisperModel m : WhisperModelCatalog.ALL) {
            assertEquals(m.fileName.contains(".en"), m.englishOnly);
        }
    }
}
