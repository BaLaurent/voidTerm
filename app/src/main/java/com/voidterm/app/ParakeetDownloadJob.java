package com.voidterm.app;

import android.content.Context;

import com.voidterm.contracts.DownloadJob;
import com.voidterm.contracts.FileSpec;
import com.voidterm.voice.ParakeetModelManager;

import java.util.List;

/** DownloadJob policy for the Parakeet ONNX bundle (4 fixed files). */
public final class ParakeetDownloadJob implements DownloadJob {

    public static final String ID = "parakeet";

    private final Context context;

    public ParakeetDownloadJob(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public String id() { return ID; }

    @Override public String displayName() { return "Parakeet model"; }

    @Override public List<FileSpec> files() {
        return ParakeetModelManager.fileSpecs(context);
    }

    @Override public void onComplete(Context context) {
        context.getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(SettingsDialog.KEY_MODEL_RELOAD_REQUESTED, true).apply();
    }
}
