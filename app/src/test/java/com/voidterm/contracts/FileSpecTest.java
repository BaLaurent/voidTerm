package com.voidterm.contracts;

import org.junit.Test;
import java.io.File;
import static org.junit.Assert.assertEquals;

public class FileSpecTest {
    @Test
    public void storesAllFields() {
        File dest = new File("/tmp/ggml-base.bin");
        FileSpec spec = new FileSpec("https://example/ggml-base.bin", dest, "base");
        assertEquals("https://example/ggml-base.bin", spec.url);
        assertEquals(dest, spec.destFile);
        assertEquals("base", spec.label);
    }
}
