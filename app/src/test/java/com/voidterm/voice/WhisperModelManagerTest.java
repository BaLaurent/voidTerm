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
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class WhisperModelManagerTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
    }

    private void writeModel(String fileName, int bytes) throws IOException {
        File dir = WhisperModelManager.getModelDir(ctx);
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, fileName))) {
            out.write(new byte[bytes]);
        }
    }

    @Test
    public void isDownloaded_falseWhenAbsent() {
        assertFalse(WhisperModelManager.isDownloaded(ctx, "ggml-base.bin"));
    }

    @Test
    public void isDownloaded_trueAfterWrite() throws IOException {
        writeModel("ggml-base.bin", 10);
        assertTrue(WhisperModelManager.isDownloaded(ctx, "ggml-base.bin"));
    }

    @Test
    public void isDownloaded_falseForEmptyFile() throws IOException {
        writeModel("ggml-small.bin", 0);
        assertFalse(WhisperModelManager.isDownloaded(ctx, "ggml-small.bin"));
    }

    @Test
    public void listDownloaded_returnsOnlyCatalogModelsPresent() throws IOException {
        writeModel("ggml-base.bin", 10);
        writeModel("ggml-tiny.bin", 10);
        writeModel("custom-user.bin", 10); // not in catalog → ignored
        List<String> ids = WhisperModelManager.listDownloaded(ctx);
        assertEquals(2, ids.size());
        assertTrue(ids.contains("ggml-base.bin"));
        assertTrue(ids.contains("ggml-tiny.bin"));
    }

    @Test
    public void delete_removesFile() throws IOException {
        writeModel("ggml-base.bin", 10);
        assertTrue(WhisperModelManager.delete(ctx, "ggml-base.bin"));
        assertFalse(WhisperModelManager.isDownloaded(ctx, "ggml-base.bin"));
    }

    @Test
    public void nextActiveAfterDelete_returnsRemainingModel() throws IOException {
        writeModel("ggml-base.bin", 10);
        writeModel("ggml-tiny.bin", 10);
        String next = WhisperModelManager.nextActiveAfterDelete(ctx, "ggml-base.bin");
        assertEquals("ggml-tiny.bin", next);
    }

    @Test
    public void nextActiveAfterDelete_returnsNullWhenNoneLeft() throws IOException {
        writeModel("ggml-base.bin", 10);
        assertEquals(null, WhisperModelManager.nextActiveAfterDelete(ctx, "ggml-base.bin"));
    }
}
