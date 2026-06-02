package com.voidterm.app;

import android.content.Context;

import com.voidterm.contracts.DownloadJob;
import com.voidterm.contracts.FileSpec;
import com.voidterm.voice.WhisperModelCatalog;
import com.voidterm.voice.WhisperModelManager;

import java.io.File;
import java.util.Collections;
import java.util.List;

/** DownloadJob policy for ONE Whisper ggml model. Activates it + switches engine to whisper. */
public final class WhisperDownloadJob implements DownloadJob {

    private final Context context;
    private final WhisperModelCatalog.WhisperModel model;

    public WhisperDownloadJob(Context context, WhisperModelCatalog.WhisperModel model) {
        this.context = context.getApplicationContext();
        this.model = model;
    }

    @Override public String id() { return model.fileName; }

    @Override public String displayName() { return "Whisper " + model.displayName; }

    @Override public List<FileSpec> files() {
        File dest = new File(WhisperModelManager.getModelDir(context), model.fileName);
        return Collections.singletonList(new FileSpec(model.url(), dest, model.displayName));
    }

    @Override public void onComplete(Context ctx) {
        // Auto-activate AND switch the engine to whisper (Parakeet is the default; without
        // the engine switch, activating a whisper model would have no visible effect).
        ctx.getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(SettingsDialog.KEY_MODEL_NAME, model.fileName)
                .putString(SettingsDialog.KEY_TRANSCRIPTION_ENGINE, SettingsDialog.ENGINE_WHISPER)
                .putBoolean(SettingsDialog.KEY_MODEL_RELOAD_REQUESTED, true)
                .apply();
    }
}
