package com.voidterm.voice;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

public class ParakeetQuantizationTest {

    @Test
    public void hasTwoQuantizations() {
        assertEquals(2, ParakeetQuantization.ALL.size());
    }

    @Test
    public void idsAreUniqueAndKnown() {
        Set<String> ids = new HashSet<>();
        for (ParakeetQuantization q : ParakeetQuantization.ALL) {
            assertTrue(ids.add(q.id));
        }
        assertTrue(ids.contains("int8"));
        assertTrue(ids.contains("fp32"));
    }

    @Test
    public void int8_filesAreInlineNoData() {
        ParakeetQuantization q = ParakeetQuantization.byId("int8");
        assertEquals("encoder-model.int8.onnx", q.encoderFile);
        assertEquals("decoder_joint-model.int8.onnx", q.decoderFile);
        assertEquals(0, q.extraFiles.length);
        assertTrue(q.sizeMb > 0);
    }

    @Test
    public void fp32_hasExternalDataFile() {
        ParakeetQuantization q = ParakeetQuantization.byId("fp32");
        assertEquals("encoder-model.onnx", q.encoderFile);
        assertEquals("decoder_joint-model.onnx", q.decoderFile);
        assertEquals(1, q.extraFiles.length);
        assertEquals("encoder-model.onnx.data", q.extraFiles[0]);
    }

    @Test
    public void allFiles_includesCommonPlusSpecific() {
        ParakeetQuantization q = ParakeetQuantization.byId("int8");
        List<String> all = q.allFiles();
        // common (2) + encoder + decoder + 0 extra = 4
        assertEquals(4, all.size());
        assertTrue(all.contains("nemo128.onnx"));
        assertTrue(all.contains("vocab.txt"));
        assertTrue(all.contains("encoder-model.int8.onnx"));
        assertTrue(all.contains("decoder_joint-model.int8.onnx"));

        // fp32: common (2) + encoder + decoder + 1 extra = 5
        assertEquals(5, ParakeetQuantization.byId("fp32").allFiles().size());
    }

    @Test
    public void url_isBaseUrlPlusFileName() {
        assertEquals(ParakeetQuantization.BASE_URL + "encoder-model.int8.onnx",
                ParakeetQuantization.byId("int8").url("encoder-model.int8.onnx"));
    }

    @Test
    public void byId_unknownReturnsNull() {
        assertNull(ParakeetQuantization.byId("nope"));
    }
}
