package com.voidterm.app;

import android.content.Context;

import com.voidterm.contracts.DownloadJob;
import com.voidterm.contracts.FileSpec;
import com.voidterm.voice.ParakeetModelManager;
import com.voidterm.voice.ParakeetQuantization;

import java.util.List;

/** DownloadJob policy for one Parakeet quantization (int8 or fp32). */
public final class ParakeetDownloadJob implements DownloadJob {

    /** Job type for the factory (EXTRA_JOB_TYPE). NOT the instance id() (= quantization id). */
    public static final String JOB_TYPE = "parakeet";

    private final Context context;
    private final ParakeetQuantization quant;

    public ParakeetDownloadJob(Context context, ParakeetQuantization quant) {
        this.context = context.getApplicationContext();
        this.quant = quant;
    }

    @Override public String id() { return quant.id; }

    @Override public String displayName() { return "Parakeet " + quant.displayName; }

    @Override public List<FileSpec> files() {
        return ParakeetModelManager.fileSpecs(context, quant);
    }

    @Override public void onComplete(Context ctx) {
        // Auto-activate the downloaded quantization + full engine reload.
        ctx.getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(SettingsDialog.KEY_PARAKEET_QUANTIZATION, quant.id)
                .putBoolean(SettingsDialog.KEY_MODEL_RELOAD_REQUESTED, true)
                .apply();
    }
}
