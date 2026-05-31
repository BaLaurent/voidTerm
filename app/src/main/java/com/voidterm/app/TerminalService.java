package com.voidterm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

/**
 * Foreground service that owns terminal sessions.
 * Sessions survive Activity destruction; the Activity is just a UI client.
 * Stops itself when no sessions remain.
 */
public class TerminalService extends Service {

    private static final String TAG = "TerminalService";
    private static final String CHANNEL_ID = "voidterm_sessions";
    private static final int NOTIFICATION_ID = 1;

    public static final String ACTION_STOP_SESSION = "com.voidterm.action.STOP_SESSION";
    public static final String ACTION_STOP_SERVICE = "com.voidterm.action.STOP_SERVICE";
    private static final String ACTION_REPOST_NOTIFICATION = "com.voidterm.action.REPOST";

    private final IBinder binder = new LocalBinder();
    private SessionManager sessionManager;
    private final HeadlessSessionClient headlessClient = new HeadlessSessionClient();

    public class LocalBinder extends Binder {
        TerminalService getService() {
            return TerminalService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sessionManager = new SessionManager();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP_SESSION.equals(action)) {
                handleStopCurrentSession();
                return START_STICKY;
            }
            if (ACTION_STOP_SERVICE.equals(action)) {
                handleStopService();
                return START_STICKY;
            }
            if (ACTION_REPOST_NOTIFICATION.equals(action)) {
                Log.i(TAG, "Notification dismissed, re-posting foreground notification");
                startForeground(NOTIFICATION_ID, buildNotification());
                return START_STICKY;
            }
        }
        // START_STICKY with null intent = service restarted after kill
        startForeground(NOTIFICATION_ID, buildNotification());
        return START_STICKY;
    }

    private void handleStopCurrentSession() {
        TerminalSession current = sessionManager.getCurrentSession();
        if (current != null) {
            current.finishIfRunning();
            sessionManager.removeSession(current);
            updateNotification();
            Log.i(TAG, "Stopped current session via notification action");
        }
    }

    private void handleStopService() {
        Log.i(TAG, "Stopping all sessions via notification action");
        for (TerminalSession session : sessionManager.getSessions()) {
            session.finishIfRunning();
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // App swiped from recents — re-assert foreground to keep sessions alive
        startForeground(NOTIFICATION_ID, buildNotification());
        super.onTaskRemoved(rootIntent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    /**
     * Swap all sessions to HeadlessSessionClient when Activity detaches.
     */
    public void detachActivity() {
        for (TerminalSession session : sessionManager.getSessions()) {
            session.updateTerminalSessionClient(headlessClient);
        }
        sessionManager.setListener(null);
        Log.i(TAG, "Activity detached, sessions using headless client");
    }

    public void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
        stopIfNoSessions();
    }

    private void stopIfNoSessions() {
        if (sessionManager.getSessionCount() == 0) {
            Log.i(TAG, "No sessions remaining, stopping service");
            stopForeground(true);
            stopSelf();
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Terminal Sessions", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows when terminal sessions are running");
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        int count = sessionManager.getSessionCount();
        String text = count == 1 ? "1 session running" : count + " sessions running";

        Intent tapIntent = new Intent(this, TermuxActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tapPi = PendingIntent.getActivity(this, 0, tapIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stopSessionIntent = new Intent(this, TerminalService.class);
        stopSessionIntent.setAction(ACTION_STOP_SESSION);
        PendingIntent stopSessionPi = PendingIntent.getService(this, 1, stopSessionIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stopServiceIntent = new Intent(this, TerminalService.class);
        stopServiceIntent.setAction(ACTION_STOP_SERVICE);
        PendingIntent stopServicePi = PendingIntent.getService(this, 2, stopServiceIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent repostIntent = new Intent(this, TerminalService.class);
        repostIntent.setAction(ACTION_REPOST_NOTIFICATION);
        PendingIntent repostPi = PendingIntent.getService(this, 3, repostIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("VoidTerm")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(tapPi)
                .setDeleteIntent(repostPi)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        "Stop Session", stopSessionPi)
                .addAction(android.R.drawable.ic_delete,
                        "Stop VoidTerm", stopServicePi)
                .build();
    }

    /**
     * Minimal session client for when Activity is not connected.
     * Handles session exit cleanup; all UI callbacks are no-ops.
     */
    private class HeadlessSessionClient implements TerminalSessionClient {
        private final Handler mainHandler = new Handler(Looper.getMainLooper());

        @Override
        public void onTextChanged(@NonNull TerminalSession changedSession) {}

        @Override
        public void onTitleChanged(@NonNull TerminalSession changedSession) {}

        @Override
        public void onSessionFinished(@NonNull TerminalSession finishedSession) {
            Log.i(TAG, "Headless: session finished: " + finishedSession.mSessionName);
            mainHandler.post(() -> {
                sessionManager.removeSession(finishedSession);
                updateNotification();
            });
        }

        @Override
        public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {}

        @Override
        public void onPasteTextFromClipboard(@Nullable TerminalSession session) {}

        @Override
        public void onBell(@NonNull TerminalSession session) {}

        @Override
        public void onColorsChanged(@NonNull TerminalSession changedSession) {}

        @Override
        public void onTerminalCursorStateChange(boolean state) {}

        @Override
        public void setTerminalShellPid(@NonNull TerminalSession session, int pid) {}

        @Override
        public Integer getTerminalCursorStyle() { return null; }

        @Override
        public void logError(String tag, String message) { Log.e(tag, message); }

        @Override
        public void logWarn(String tag, String message) { Log.w(tag, message); }

        @Override
        public void logInfo(String tag, String message) { Log.i(tag, message); }

        @Override
        public void logDebug(String tag, String message) { Log.d(tag, message); }

        @Override
        public void logVerbose(String tag, String message) { Log.v(tag, message); }

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) {
            Log.e(tag, message, e);
        }

        @Override
        public void logStackTrace(String tag, Exception e) {
            Log.e(tag, "Stack trace", e);
        }
    }
}
