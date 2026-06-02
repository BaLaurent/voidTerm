package com.voidterm.voice;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Filesystem state of downloaded Whisper models. They live directly in
 * {filesDir}/models/ — the directory WhisperBridge loads from and the file picker
 * copies into. No HTTP here (the download runs in ModelDownloadService).
 */
public final class WhisperModelManager {

    private static final String MODELS_DIR = "models";

    private WhisperModelManager() {}

    public static File getModelDir(Context context) {
        return new File(context.getFilesDir(), MODELS_DIR);
    }

    /** True if {@code fileName} exists with non-zero size. */
    public static boolean isDownloaded(Context context, String fileName) {
        File f = new File(getModelDir(context), fileName);
        return f.exists() && f.length() > 0;
    }

    /** Catalog models currently present on disk (by fileName). Ignores non-catalog files. */
    public static List<String> listDownloaded(Context context) {
        List<String> present = new ArrayList<>();
        for (WhisperModelCatalog.WhisperModel m : WhisperModelCatalog.ALL) {
            if (isDownloaded(context, m.fileName)) present.add(m.fileName);
        }
        return present;
    }

    /** Delete a model file. Returns true if a file was removed. */
    public static boolean delete(Context context, String fileName) {
        File f = new File(getModelDir(context), fileName);
        return f.exists() && f.delete();
    }

    /**
     * The fileName that should become active after {@code deletedFileName} is removed:
     * the first remaining downloaded catalog model, or null if none remain.
     */
    public static String nextActiveAfterDelete(Context context, String deletedFileName) {
        for (WhisperModelCatalog.WhisperModel m : WhisperModelCatalog.ALL) {
            if (m.fileName.equals(deletedFileName)) continue;
            if (isDownloaded(context, m.fileName)) return m.fileName;
        }
        return null;
    }
}
