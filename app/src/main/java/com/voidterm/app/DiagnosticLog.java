package com.voidterm.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * On-screen diagnostic log for VoidTerm.
 * Displays colored log messages directly on the device screen
 * so errors are visible without logcat/ADB.
 */
public class DiagnosticLog {

    private static final int MAX_LINES = 200;
    private static final int COLOR_INFO = 0xFF4CAF50;    // green
    private static final int COLOR_WARN = 0xFFFF9800;    // orange
    private static final int COLOR_ERROR = 0xFFF44336;   // red
    private static final int COLOR_DEBUG = 0xFF9E9E9E;   // gray
    private static final int COLOR_SYSTEM = 0xFF2196F3;  // blue

    private final TextView textView;
    private final ScrollView scrollView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int lineCount = 0;

    public DiagnosticLog(Context context) {
        scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(0xDD000000);
        scrollView.setPadding(16, 16, 16, 16);

        textView = new TextView(context);
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        textView.setTextColor(Color.WHITE);
        textView.setLineSpacing(0, 1.2f);

        scrollView.addView(textView);
    }

    public ScrollView getView() {
        return scrollView;
    }

    public void info(String tag, String message) {
        appendLine(COLOR_INFO, "I", tag, message);
    }

    public void warn(String tag, String message) {
        appendLine(COLOR_WARN, "W", tag, message);
    }

    public void error(String tag, String message) {
        appendLine(COLOR_ERROR, "E", tag, message);
    }

    public void error(String tag, String message, Throwable t) {
        appendLine(COLOR_ERROR, "E", tag, message + "\n  " + t.getClass().getSimpleName() + ": " + t.getMessage());
        StackTraceElement[] stack = t.getStackTrace();
        for (int i = 0; i < Math.min(5, stack.length); i++) {
            appendLine(COLOR_ERROR, "E", tag, "  at " + stack[i].toString());
        }
    }

    public void debug(String tag, String message) {
        appendLine(COLOR_DEBUG, "D", tag, message);
    }

    public void system(String message) {
        appendLine(COLOR_SYSTEM, "*", "SYS", message);
    }

    private void appendLine(int color, String level, String tag, String message) {
        String line = level + "/" + tag + ": " + message;
        SpannableString span = new SpannableString(line + "\n");
        span.setSpan(new ForegroundColorSpan(color), 0, span.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        Runnable action = () -> {
            textView.append(span);
            lineCount++;
            if (lineCount > MAX_LINES) {
                String text = textView.getText().toString();
                int cutIndex = text.indexOf('\n');
                if (cutIndex >= 0) {
                    textView.setText(text.substring(cutIndex + 1));
                    lineCount--;
                }
            }
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            handler.post(action);
        }
    }
}
