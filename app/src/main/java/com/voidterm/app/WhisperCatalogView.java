package com.voidterm.app;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.voidterm.voice.WhisperModelCatalog;
import com.voidterm.voice.WhisperModelManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Accordion catalog of Whisper models, grouped by family. Each model row shows its state
 * (absent / downloaded-inactive / active) and a contextual action. Pure view: it reads the
 * active model name + downloaded state, and reports actions through {@link Listener}.
 */
public class WhisperCatalogView extends LinearLayout {

    public interface Listener {
        void onDownload(WhisperModelCatalog.WhisperModel model);
        void onActivate(WhisperModelCatalog.WhisperModel model);
        void onDelete(WhisperModelCatalog.WhisperModel model);
    }

    private final Listener listener;
    private final int textColor;
    private final int mutedColor;
    // Per-model row action button, keyed by fileName, for targeted progress refresh.
    private final Map<String, Button> actionButtons = new HashMap<>();
    private final Map<String, TextView> stateLabels = new HashMap<>();
    private String activeFileName;
    private boolean downloadInProgress;
    private String downloadingFileName;

    public WhisperCatalogView(Context context, Listener listener, String activeFileName,
                              int textColor, int mutedColor) {
        super(context);
        this.listener = listener;
        this.activeFileName = activeFileName;
        this.textColor = textColor;
        this.mutedColor = mutedColor;
        setOrientation(VERTICAL);
        build();
    }

    private void build() {
        for (String family : WhisperModelCatalog.FAMILIES) {
            addFamily(family);
        }
    }

    private void addFamily(String family) {
        final LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(VERTICAL);
        container.setVisibility(GONE);

        final Button header = new Button(getContext());
        header.setAllCaps(false);
        header.setText("▸ " + family);
        header.setTextColor(textColor);
        header.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.setOnClickListener(v -> {
            boolean show = container.getVisibility() != VISIBLE;
            container.setVisibility(show ? VISIBLE : GONE);
            header.setText((show ? "▾ " : "▸ ") + family);
        });
        addView(header);

        for (WhisperModelCatalog.WhisperModel m : WhisperModelCatalog.ALL) {
            if (m.family.equals(family)) container.addView(makeRow(m));
        }
        addView(container);
    }

    private View makeRow(WhisperModelCatalog.WhisperModel m) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView name = new TextView(getContext());
        name.setText(m.displayName + "   " + sizeLabel(m.sizeMb));
        name.setTextColor(textColor);
        name.setTextSize(13);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        name.setLayoutParams(nameLp);
        row.addView(name);

        TextView state = new TextView(getContext());
        state.setTextColor(mutedColor);
        state.setTextSize(12);
        state.setPadding(dp(8), 0, dp(8), 0);
        stateLabels.put(m.fileName, state);
        row.addView(state);

        Button action = new Button(getContext());
        action.setAllCaps(false);
        action.setTextSize(12);
        actionButtons.put(m.fileName, action);
        row.addView(action);

        Button delete = new Button(getContext());
        delete.setAllCaps(false);
        delete.setText("🗑");
        delete.setOnClickListener(v -> listener.onDelete(m));
        row.addView(delete);

        bindRow(m, action, delete);
        return row;
    }

    /** Set the action button label + click + delete visibility from current state. */
    private void bindRow(WhisperModelCatalog.WhisperModel m, Button action, Button delete) {
        boolean downloaded = WhisperModelManager.isDownloaded(getContext(), m.fileName);
        boolean active = m.fileName.equals(activeFileName);
        TextView state = stateLabels.get(m.fileName);

        if (downloadInProgress && m.fileName.equals(downloadingFileName)) {
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
            action.setOnClickListener(v -> listener.onDownload(m));
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
            action.setOnClickListener(v -> listener.onActivate(m));
            if (state != null) state.setText("");
            delete.setVisibility(VISIBLE);
        }
    }

    /** Called by SettingsActivity on a whisper download broadcast (progress text on the row). */
    public void onProgress(String fileName, String text) {
        downloadInProgress = true;
        downloadingFileName = fileName;
        TextView state = stateLabels.get(fileName);
        if (state != null) state.setText(text);
        refreshAll();
    }

    /** Download finished (complete or error): re-read disk state, set the new active model. */
    public void onDownloadEnded(String newActiveFileName) {
        downloadInProgress = false;
        downloadingFileName = null;
        if (newActiveFileName != null) activeFileName = newActiveFileName;
        refreshAll();
    }

    public void setActive(String fileName) {
        activeFileName = fileName;
        refreshAll();
    }

    private void refreshAll() {
        for (WhisperModelCatalog.WhisperModel m : WhisperModelCatalog.ALL) {
            Button action = actionButtons.get(m.fileName);
            if (action == null) continue;
            // delete button is the next sibling in the row
            LinearLayout row = (LinearLayout) action.getParent();
            Button delete = (Button) row.getChildAt(row.getChildCount() - 1);
            bindRow(m, action, delete);
        }
    }

    private static String sizeLabel(int mb) {
        return mb >= 1024 ? String.format(java.util.Locale.US, "%.1f GB", mb / 1024f) : mb + " MB";
    }

    private int dp(int v) { return PanelUtils.dp(getContext(), v); }
}
