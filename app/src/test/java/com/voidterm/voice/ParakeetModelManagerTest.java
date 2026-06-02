package com.voidterm.voice;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class ParakeetModelManagerTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
    }

    private void write(String fileName, int bytes) throws IOException {
        File dir = ParakeetModelManager.getModelDir(ctx);
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, fileName))) {
            out.write(new byte[bytes]);
        }
    }

    private void writeAll(ParakeetQuantization q) throws IOException {
        for (String f : q.allFiles()) write(f, 10);
    }

    @Test
    public void isModelComplete_falseWhenMissing() {
        assertFalse(ParakeetModelManager.isModelComplete(ctx, ParakeetQuantization.INT8));
    }

    @Test
    public void isModelComplete_trueWhenAllPresent() throws IOException {
        writeAll(ParakeetQuantization.INT8);
        assertTrue(ParakeetModelManager.isModelComplete(ctx, ParakeetQuantization.INT8));
    }

    @Test
    public void isModelComplete_falseWhenAnEmptyFile() throws IOException {
        writeAll(ParakeetQuantization.INT8);
        // overwrite one with empty
        write("encoder-model.int8.onnx", 0);
        assertFalse(ParakeetModelManager.isModelComplete(ctx, ParakeetQuantization.INT8));
    }

    @Test
    public void fileSpecs_countMatchesAllFiles() {
        assertEquals(4, ParakeetModelManager.fileSpecs(ctx, ParakeetQuantization.INT8).size());
        assertEquals(5, ParakeetModelManager.fileSpecs(ctx, ParakeetQuantization.FP32).size());
    }

    @Test
    public void delete_removesSpecificFilesButKeepsCommon() throws IOException {
        writeAll(ParakeetQuantization.INT8);
        ParakeetModelManager.deleteModels(ctx, ParakeetQuantization.INT8);
        File dir = ParakeetModelManager.getModelDir(ctx);
        // specific gone
        assertFalse(new File(dir, "encoder-model.int8.onnx").exists());
        assertFalse(new File(dir, "decoder_joint-model.int8.onnx").exists());
        // common always kept
        assertTrue(new File(dir, "nemo128.onnx").exists());
        assertTrue(new File(dir, "vocab.txt").exists());
    }

    @Test
    public void delete_int8_doesNotTouchFp32Files() throws IOException {
        writeAll(ParakeetQuantization.INT8);
        writeAll(ParakeetQuantization.FP32);
        ParakeetModelManager.deleteModels(ctx, ParakeetQuantization.INT8);
        File dir = ParakeetModelManager.getModelDir(ctx);
        assertTrue(new File(dir, "encoder-model.onnx").exists());
        assertTrue(new File(dir, "encoder-model.onnx.data").exists());
        assertTrue(new File(dir, "decoder_joint-model.onnx").exists());
    }
}
