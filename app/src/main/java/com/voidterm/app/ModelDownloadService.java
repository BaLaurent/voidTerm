package com.voidterm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.voidterm.contracts.DownloadJob;
import com.voidterm.contracts.FileSpec;
import com.voidterm.voice.HttpModelDownloader;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that owns ANY model download (Parakeet bundle or one Whisper model).
 * MECHANISM only: foreground lifecycle, wakelock, notification, cancellation, the
 * skip/.tmp/rename loop. The POLICY (what to download) is a {@link DownloadJob} rebuilt
 * from the start Intent via {@link DownloadJobs}.
 */
public class ModelDownloadService extends Service {

    private static final String TAG = "ModelDownloadService";
    private static final String CHANNEL_ID = "voidterm_download";
    private static final int NOTIFICATION_ID = 2;
    private static final long WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L;

    public static final String ACTION_START_DOWNLOAD = "com.voidterm.action.START_DOWNLOAD";
    public static final String ACTION_CANCEL_DOWNLOAD = "com.voidterm.action.CANCEL_DOWNLOAD";

    public static final String BROADCAST_PROGRESS = "com.voidterm.action.DOWNLOAD_PROGRESS";
    public static final String BROADCAST_COMPLETE = "com.voidterm.action.DOWNLOAD_COMPLETE";
    public static final String BROADCAST_ERROR = "com.voidterm.action.DOWNLOAD_ERROR";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_PERCENT = "percent";       // current-file %, -1 if unknown
    public static final String EXTRA_MODEL_ID = "model_id";     // job id, routes UI updates

    /** Sentinel used when a download is cancelled. */
    public static final String CANCELLED = "Cancelled";

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile String lastProgressText;
    private static volatile String runningJobId;

    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private PowerManager.WakeLock wakeLock;

    public static boolean isRunning() { return running.get(); }
    public static String lastProgressText() { return lastProgressText; }
    /** Id of the job currently downloading, or null. Lets the UI know which line is active. */
    public static String runningJobId() { return runningJobId; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_CANCEL_DOWNLOAD.equals(action)) {
            cancelFlag.set(true);
            return START_NOT_STICKY;
        }

        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "Download already running, ignoring start");
            return START_NOT_STICKY;
        }

        DownloadJob job = DownloadJobs.fromIntent(getApplicationContext(), intent);
        if (job == null) {
            Log.e(TAG, "No valid DownloadJob in intent");
            running.set(false);
            return START_NOT_STICKY;
        }

        cancelFlag.set(false);
        runningJobId = job.id();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildProgressNotification(job.displayName(),
                "Starting download…", -1));
        acquireWakeLock();
        startDownloadThread(job);
        return START_NOT_STICKY;
    }

    private void startDownloadThread(DownloadJob job) {
        final Context ctx = getApplicationContext();
        new Thread(() -> runJob(ctx, job), "ModelDownload").start();
    }

    /** The generic skip/.tmp/rename loop, migrated from ParakeetModelManager.download(). */
    private void runJob(Context ctx, DownloadJob job) {
        List<FileSpec> files = job.files();
        int total = files.size();
        try {
            for (int i = 0; i < total; i++) {
                if (cancelFlag.get()) { finishCancelled(job); return; }

                FileSpec spec = files.get(i);
                File dest = spec.destFile;
                File dir = dest.getParentFile();
                if (dir != null && !dir.exists() && !dir.mkdirs()) {
                    finishError(job, "Failed to create model directory");
                    return;
                }
                if (dest.exists() && dest.length() > 0) {
                    broadcastProgress(job, spec.label + " ready (" + (i + 1) + "/" + total + ")", -1);
                    continue;
                }

                File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
                final int idx = i;
                try {
                    HttpModelDownloader.download(spec.url, tmp, cancelFlag, (done, totalBytes) -> {
                        int percent = totalBytes > 0 ? (int) (done * 100 / totalBytes) : -1;
                        String mb = String.format(Locale.US, "%.1f", done / 1048576f);
                        String totMb = totalBytes > 0
                                ? String.format(Locale.US, "%.1f", totalBytes / 1048576f) : "?";
                        broadcastProgress(job, "Downloading " + spec.label + " ("
                                + (idx + 1) + "/" + total + "): " + mb + " / " + totMb + " MB", percent);
                    });
                    if (!tmp.renameTo(dest)) {
                        throw new IOException("Failed to rename " + tmp.getName());
                    }
                    broadcastProgress(job, spec.label + " complete (" + (i + 1) + "/" + total + ")", -1);
                } catch (IOException e) {
                    if (tmp.exists()) tmp.delete();
                    if (cancelFlag.get()) { finishCancelled(job); return; }
                    Log.e(TAG, "Download failed", e);
                    finishError(job, "Failed to download " + spec.label + ": " + e.getMessage());
                    return;
                }
            }
            job.onComplete(ctx);
            broadcast(BROADCAST_COMPLETE, job.id(), "All files downloaded", 100);
            finish(job.displayName() + " ready", false);
        } catch (Exception e) {
            Log.e(TAG, "Download failed", e);
            finishError(job, "Download failed: " + e.getMessage());
        }
    }

    private void broadcastProgress(DownloadJob job, String text, int percent) {
        lastProgressText = text;
        updateNotification(job.displayName(), text, percent);
        broadcast(BROADCAST_PROGRESS, job.id(), text, percent);
    }

    private void finishError(DownloadJob job, String error) {
        broadcast(BROADCAST_ERROR, job.id(), error, -1);
        finish("Download failed: " + error, false);
    }

    private void finishCancelled(DownloadJob job) {
        broadcast(BROADCAST_ERROR, job.id(), CANCELLED, -1);
        finish(null, true);
    }

    private void finish(String doneText, boolean cancelled) {
        releaseWakeLock();
        lastProgressText = null;
        runningJobId = null;
        running.set(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (!cancelled && doneText != null) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildDoneNotification(doneText));
        }
        stopSelf();
    }

    // --- Wake lock ---

    private void acquireWakeLock() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voidterm:model-download");
        wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    // --- Notifications ---

    private void createNotificationChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Model Download", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification buildProgressNotification(String title, String text, int percent) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, SettingsActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent cancel = PendingIntent.getService(this, 1,
                new Intent(this, ModelDownloadService.class).setAction(ACTION_CANCEL_DOWNLOAD),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Downloading " + title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel);
        if (percent >= 0) b.setProgress(100, percent, false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private void updateNotification(String title, String text, int percent) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildProgressNotification(title, text, percent));
    }

    private Notification buildDoneNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, SettingsActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("VoidTerm")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build();
    }

    // --- Broadcast ---

    private void broadcast(String action, String modelId, String text, int percent) {
        Intent i = new Intent(action)
                .setPackage(getPackageName())
                .putExtra(EXTRA_MODEL_ID, modelId)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_PERCENT, percent);
        sendBroadcast(i);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseWakeLock();
        running.set(false);
        runningJobId = null;
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }
}
