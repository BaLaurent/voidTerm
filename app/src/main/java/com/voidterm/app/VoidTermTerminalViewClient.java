package com.voidterm.app;

import android.content.Context;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;

import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalViewClient;

/**
 * Implements TerminalViewClient for VoidTerm.
 * Handles terminal view callbacks including key events, gestures, and Quest input.
 */
public class VoidTermTerminalViewClient implements TerminalViewClient {

    private static final String TAG = "VoidTermViewClient";
    private final TermuxActivity activity;

    public VoidTermTerminalViewClient(TermuxActivity activity) {
        this.activity = activity;
    }

    @Override
    public float onScale(float scale) {
        if (scale < 0.9f || scale > 1.1f) {
            int currentSize = 20;
            int newSize = (int) (currentSize * scale);
            newSize = Math.max(6, Math.min(256, newSize));
            if (activity.getTerminalView() != null) {
                activity.getTerminalView().setTextSize(newSize);
            }
        }
        return 1.0f;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        // Toggle soft keyboard on tap — toggleSoftInput is more reliable than
        // showSoftInput on Quest and other devices where focus state can be tricky
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0);
        }
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return true;
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        return true;
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        return false;
    }

    @Override
    public boolean isTerminalViewSelected() {
        return true; // Single-session MVP — always selected
    }

    @Override
    public void copyModeChanged(boolean copyMode) {
        // Not needed for MVP
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
        // Let default handling apply
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        return false;
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        // Could show context menu — not needed for MVP
        return false;
    }

    @Override
    public boolean readControlKey() {
        GameBoyControlPanel panel = activity.getControlPanel();
        if (panel != null && panel.isCtrlActive()) {
            panel.resetCtrl();
            return true;
        }
        return false;
    }

    @Override
    public boolean readAltKey() {
        return false;
    }

    @Override
    public boolean readShiftKey() {
        GameBoyControlPanel panel = activity.getControlPanel();
        if (panel != null && panel.isShiftActive()) {
            panel.resetShift();
            return true;
        }
        return false;
    }

    @Override
    public boolean readFnKey() {
        return false;
    }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        return false;
    }

    @Override
    public void onEmulatorSet() {
        // Terminal emulator connected to view
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
