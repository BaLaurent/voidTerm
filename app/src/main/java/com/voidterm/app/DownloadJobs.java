package com.voidterm.app;

import android.content.Context;
import android.content.Intent;

import com.voidterm.contracts.DownloadJob;

/** Rebuilds a DownloadJob from a Service Intent (Services receive Intents, not objects). */
public final class DownloadJobs {

    public static final String EXTRA_JOB_TYPE = "job_type";
    public static final String EXTRA_MODEL_ID = "model_id";
    public static final String JOB_WHISPER = "whisper";

    private DownloadJobs() {}

    public static DownloadJob fromIntent(Context context, Intent intent) {
        String type = intent.getStringExtra(EXTRA_JOB_TYPE);
        if (ParakeetDownloadJob.ID.equals(type)) {
            return new ParakeetDownloadJob(context);
        }
        if (JOB_WHISPER.equals(type)) {
            String modelId = intent.getStringExtra(EXTRA_MODEL_ID);
            com.voidterm.voice.WhisperModelCatalog.WhisperModel m =
                    com.voidterm.voice.WhisperModelCatalog.byId(modelId);
            return m == null ? null : new WhisperDownloadJob(context, m);
        }
        return null;
    }
}
