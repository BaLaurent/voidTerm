package com.voidterm.app;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.voidterm.voice.ParakeetModelManager;
import com.voidterm.voice.ParakeetQuantization;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Two-row selector for the Parakeet quantization (int8 / fp32). Each row shows its state
 * (absent / downloaded-inactive / active) and a contextual action. Pure view: reports
 * actions through {@link Listener}; refreshed by the download broadcast via the controller.
 */
public class ParakeetQuantizationView extends LinearLayout {

    public interface Listener {
        void onDownload(ParakeetQuantization q);
        void onActivate(ParakeetQuantization q);
        void onDelete(ParakeetQuantization q);
    }

    private final Listener listener;
    private final int textColor;
    private final int mutedColor;
    private final Map<String, Button> actionButtons = new HashMap<>();
    private final Map<String, Button> deleteButtons = new HashMap<>();
    private final Map<String, TextView> stateLabels = new HashMap<>();
    private String activeId;
    private boolean downloadInProgress;
    private String downloadingId;

    public ParakeetQuantizationView(Context context, Listener listener, String activeId,
                                    int textColor, int mutedColor) {
        super(context);
        this.listener = listener;
        this.activeId = activeId;
        this.textColor = textColor;
        this.mutedColor = mutedColor;
        setOrientation(VERTICAL);
        for (ParakeetQuantization q : ParakeetQuantization.ALL) {
            addView(makeRow(q));
        }
    }

    private View makeRow(ParakeetQuantization q) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView name = new TextView(getContext());
        name.setText(q.displayName + "   " + sizeLabel(q.sizeMb));
        name.setTextColor(textColor);
        name.setTextSize(13);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);

        TextView state = new TextView(getContext());
        state.setTextColor(mutedColor);
        state.setTextSize(12);
        state.setPadding(dp(8), 0, dp(8), 0);
        stateLabels.put(q.id, state);
        row.addView(state);

        Button action = new Button(getContext());
        action.setAllCaps(false);
        action.setTextSize(12);
        actionButtons.put(q.id, action);
        row.addView(action);

        Button delete = new Button(getContext());
        delete.setAllCaps(false);
        delete.setText("🗑");
        delete.setOnClickListener(v -> listener.onDelete(q));
        deleteButtons.put(q.id, delete);
        row.addView(delete);

        bindRow(q, action, delete);
        return row;
    }

    private void bindRow(ParakeetQuantization q, Button action, Button delete) {
        boolean downloaded = ParakeetModelManager.isModelComplete(getContext(), q);
        boolean active = q.id.equals(activeId);
        TextView state = stateLabels.get(q.id);

        if (downloadInProgress && q.id.equals(downloadingId)) {
            action.setText("Downloading…");
            action.setEnabled(false);
            action.setOnClickListener(null);
            if (state != null) state.setText("");
            delete.setVisibility(GONE);
            return;
        }
        action.setEnabled(!downloadInProgress);
        if (!downloaded) {
            action.setText("Download");
            action.setOnClickListener(v -> listener.onDownload(q));
            if (state != null) state.setText("");
            delete.setVisibility(GONE);
        } else if (active) {
            action.setText("✓ Active");
            action.setOnClickListener(null);
            action.setEnabled(false);
            if (state != null) state.setText("");
            delete.setVisibility(VISIBLE);
        } else {
            action.setText("Activate");
            action.setOnClickListener(v -> listener.onActivate(q));
            if (state != null) state.setText("");
            delete.setVisibility(VISIBLE);
        }
    }

    public void onProgress(String quantId, String text) {
        downloadInProgress = true;
        downloadingId = quantId;
        TextView state = stateLabels.get(quantId);
        if (state != null) state.setText(text);
        refreshAll();
    }

    public void onDownloadEnded(String newActiveId) {
        downloadInProgress = false;
        downloadingId = null;
        if (newActiveId != null) activeId = newActiveId;
        refreshAll();
    }

    public void setActive(String quantId) {
        activeId = quantId;
        refreshAll();
    }

    private void refreshAll() {
        for (ParakeetQuantization q : ParakeetQuantization.ALL) {
            Button action = actionButtons.get(q.id);
            Button delete = deleteButtons.get(q.id);
            if (action == null || delete == null) continue;
            bindRow(q, action, delete);
        }
    }

    private static String sizeLabel(int mb) {
        return mb >= 1024 ? String.format(Locale.US, "%.1f GB", mb / 1024f) : mb + " MB";
    }

    private int dp(int v) { return PanelUtils.dp(getContext(), v); }
}
