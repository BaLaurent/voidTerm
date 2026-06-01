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

import com.voidterm.voice.ParakeetModelManager;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that owns the Parakeet model download (~534 MB).
 *
 * The download survives Activity navigation and backgrounding because the service
 * runs in the foreground (keeps the process alive) and holds a partial wake lock
 * (keeps the CPU/network alive while the Quest screen sleeps).
 *
 * Responsibility split: this service is the MECHANISM (foreground lifecycle,
 * notification, wake lock, cancellation). {@link ParakeetModelManager#download}
 * is the LOGIC (the actual HTTP transfer). The service runs that blocking call on
 * its own thread and routes progress to the notification + a private broadcast that
 * {@link SettingsActivity} observes while open.
 */
public class ParakeetDownloadService extends Service {

    private static final String TAG = "ParakeetDownloadService";
    private static final String CHANNEL_ID = "voidterm_download";
    // Distinct from TerminalService's notification (ID 1) — both can coexist.
    private static final int NOTIFICATION_ID = 2;
    // Safety cap: never hold the wake lock indefinitely (Android best practice).
    private static final long WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L;

    public static final String ACTION_START_DOWNLOAD = "com.voidterm.action.START_DOWNLOAD";
    public static final String ACTION_CANCEL_DOWNLOAD = "com.voidterm.action.CANCEL_DOWNLOAD";

    // Private broadcasts for the Settings UI (sent with setPackage, received NOT_EXPORTED).
    public static final String BROADCAST_PROGRESS = "com.voidterm.action.DOWNLOAD_PROGRESS";
    public static final String BROADCAST_COMPLETE = "com.voidterm.action.DOWNLOAD_COMPLETE";
    public static final String BROADCAST_ERROR = "com.voidterm.action.DOWNLOAD_ERROR";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_PERCENT = "percent"; // current-file %, -1 if unknown

    /** True while a download is in progress — read by SettingsActivity to seed its UI. */
    private static final AtomicBoolean running = new AtomicBoolean(false);
    /** Last human-readable progress line, for an Activity reopened mid-download. */
    private static volatile String lastProgressText;

    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private PowerManager.WakeLock wakeLock;

    public static boolean isRunning() {
        return running.get();
    }

    public static String lastProgressText() {
        return lastProgressText;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_CANCEL_DOWNLOAD.equals(action)) {
            // Trip the flag; the download thread observes it and finishes via finishCancelled().
            cancelFlag.set(true);
            return START_NOT_STICKY;
        }

        // ACTION_START_DOWNLOAD (default). Reject duplicate starts.
        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "Download already running, ignoring start");
            return START_NOT_STICKY;
        }

        cancelFlag.set(false);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildProgressNotification("Starting download…", -1));
        acquireWakeLock();
        startDownloadThread();
        return START_NOT_STICKY;
    }

    private void startDownloadThread() {
        final Context ctx = getApplicationContext();
        new Thread(() -> ParakeetModelManager.download(ctx, new ParakeetModelManager.ProgressCallback() {
            @Override
            public void onProgress(String fileName, int fileIndex, int totalFiles,
                                   long bytesDownloaded, long totalBytes) {
                int percent = totalBytes > 0 ? (int) (bytesDownloaded * 100 / totalBytes) : -1;
                String mb = String.format(Locale.US, "%.1f", bytesDownloaded / 1048576f);
                String totalMb = totalBytes > 0
                        ? String.format(Locale.US, "%.1f", totalBytes / 1048576f) : "?";
                String text = "Downloading " + fileName + " (" + (fileIndex + 1) + "/" + totalFiles
                        + "): " + mb + " / " + totalMb + " MB";
                lastProgressText = text;
                updateNotification(text, percent);
                broadcast(BROADCAST_PROGRESS, text, percent);
            }

            @Override
            public void onFileComplete(String fileName, int fileIndex, int totalFiles) {
                String text = fileName + " complete (" + (fileIndex + 1) + "/" + totalFiles + ")";
                lastProgressText = text;
                updateNotification(text, -1);
                broadcast(BROADCAST_PROGRESS, text, -1);
            }

            @Override
            public void onComplete() {
                getSharedPreferences(SettingsDialog.PREFS_NAME, MODE_PRIVATE)
                        .edit().putBoolean(SettingsDialog.KEY_MODEL_RELOAD_REQUESTED, true).apply();
                broadcast(BROADCAST_COMPLETE, "All models downloaded", 100);
                finish("Parakeet model ready", false);
            }

            @Override
            public void onError(String error) {
                if (cancelFlag.get() || ParakeetModelManager.CANCELLED.equals(error)) {
                    broadcast(BROADCAST_ERROR, ParakeetModelManager.CANCELLED, -1);
                    finish(null, true);
                } else {
                    broadcast(BROADCAST_ERROR, error, -1);
                    finish("Download failed: " + error, false);
                }
            }
        }, cancelFlag), "ParakeetModelDownload").start();
    }

    /**
     * Tear down the service. When {@code cancelled}, the ongoing notification is just
     * removed; otherwise a dismissable terminal notification ({@code doneText}) is posted.
     */
    private void finish(String doneText, boolean cancelled) {
        releaseWakeLock();
        lastProgressText = null;
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
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voidterm:parakeet-download");
        wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
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

    private Notification buildProgressNotification(String text, int percent) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, SettingsActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent cancel = PendingIntent.getService(this, 1,
                new Intent(this, ParakeetDownloadService.class).setAction(ACTION_CANCEL_DOWNLOAD),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Downloading Parakeet model")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel);
        if (percent >= 0) {
            b.setProgress(100, percent, false);
        } else {
            b.setProgress(0, 0, true); // indeterminate (size unknown yet)
        }
        return b.build();
    }

    private void updateNotification(String text, int percent) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildProgressNotification(text, percent));
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

    // --- Broadcast to the Settings UI ---

    private void broadcast(String action, String text, int percent) {
        Intent i = new Intent(action)
                .setPackage(getPackageName())
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_PERCENT, percent);
        sendBroadcast(i);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Belt-and-suspenders: never leak the wake lock or leave the flag stuck on.
        releaseWakeLock();
        running.set(false);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // started service, not bound
    }
}
