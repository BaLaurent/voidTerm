package com.voidterm.contracts;

import android.content.Context;
import java.util.List;

/**
 * Policy for a model download: describes WHAT to download and what to do on success.
 * The MECHANISM (foreground service, wakelock, notification, HTTP transfer) lives in
 * ModelDownloadService + HttpModelDownloader. This is a strategy (composition over
 * inheritance), not a plain-data DTO — the DTO it carries is {@link FileSpec}.
 */
public interface DownloadJob {
    /** Stable identifier of the download target (e.g. "parakeet" or a whisper file id). */
    String id();

    /** Human-readable name for the notification (e.g. "Parakeet model", "Whisper base"). */
    String displayName();

    /** Ordered list of files to fetch. Already-present files are skipped by the runner. */
    List<FileSpec> files();

    /** Called once after all files succeed. Persists prefs (reload flag, active model, engine). */
    void onComplete(Context context);
}
