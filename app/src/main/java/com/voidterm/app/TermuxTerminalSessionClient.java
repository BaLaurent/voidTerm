package com.voidterm.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

/**
 * Implements TerminalSessionClient for VoidTerm.
 * Handles terminal session callbacks and voice text injection into the PTY.
 */
public class TermuxTerminalSessionClient implements TerminalSessionClient {

    private static final String TAG = "TermuxSessionClient";

    private final Activity activity;
    private final DiagnosticLog diagnosticLog;
    private TerminalSession currentSession;

    public TermuxTerminalSessionClient(Activity activity, DiagnosticLog diagnosticLog) {
        this.activity = activity;
        this.diagnosticLog = diagnosticLog;
    }

    /**
     * Set the active terminal session.
     */
    public void setSession(TerminalSession session) {
        this.currentSession = session;
    }

    /**
     * Inject voice-transcribed text into the active terminal PTY.
     * Uses TerminalSession.write(String) which handles UTF-8 encoding.
     * Does NOT append a newline — user must press Enter to execute.
     */
    public void injectVoiceText(String text) {
        if (text == null || text.isEmpty()) {
            Log.w(TAG, "Empty text, nothing to inject");
            return;
        }
        if (currentSession == null || !currentSession.isRunning()) {
            Log.w(TAG, "No active terminal session, cannot inject text");
            return;
        }
        currentSession.write(text);
        Log.i(TAG, "Injected voice text (" + text.length() + " chars)");
    }

    /**
     * Check if a terminal session is active and running.
     */
    public boolean hasActiveSession() {
        return currentSession != null && currentSession.isRunning();
    }

    // --- TerminalSessionClient interface ---

    @Override
    public void onTextChanged(@NonNull TerminalSession changedSession) {
        // Notify the TerminalView to redraw
        activity.runOnUiThread(() -> {
            if (activity instanceof TermuxActivity) {
                TermuxActivity ta = (TermuxActivity) activity;
                if (ta.getTerminalView() != null) {
                    ta.getTerminalView().onScreenUpdated();
                }
            }
        });
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession changedSession) {
        // Could update activity title — not needed for MVP
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        Log.w(TAG, "Terminal session finished");
        if (diagnosticLog != null) {
            diagnosticLog.warn(TAG, "Terminal session finished");
        }
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(new ClipData("VoidTerm", new String[]{"text/plain"}, new ClipData.Item(text)));
        }
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
        if (activity instanceof TermuxActivity) {
            ((TermuxActivity) activity).pasteFromClipboard();
        }
    }

    @Override
    public void onBell(@NonNull TerminalSession session) {
        // Could vibrate — not needed for MVP
    }

    @Override
    public void onColorsChanged(@NonNull TerminalSession session) {
        activity.runOnUiThread(() -> {
            if (activity instanceof TermuxActivity) {
                TermuxActivity ta = (TermuxActivity) activity;
                if (ta.getTerminalView() != null) {
                    ta.getTerminalView().onScreenUpdated();
                }
            }
        });
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
        // Cursor blink state changed — handled by TerminalView internally
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession session, int pid) {
        Log.d(TAG, "Shell PID: " + pid);
    }

    @Override
    public Integer getTerminalCursorStyle() {
        // Return null to use default cursor style
        return null;
    }

    @Override
    public void logError(String tag, String message) {
        Log.e(tag, message);
    }

    @Override
    public void logWarn(String tag, String message) {
        Log.w(tag, message);
    }

    @Override
    public void logInfo(String tag, String message) {
        Log.i(tag, message);
    }

    @Override
    public void logDebug(String tag, String message) {
        Log.d(tag, message);
    }

    @Override
    public void logVerbose(String tag, String message) {
        Log.v(tag, message);
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        Log.e(tag, message, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        Log.e(tag, "Stack trace", e);
    }
}
