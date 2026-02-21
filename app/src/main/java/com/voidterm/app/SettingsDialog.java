package com.voidterm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Settings dialog for VoidTerm. Displays current whisper model name
 * and provides a file picker to select a different model.
 */
public class SettingsDialog {

    static final String PREFS_NAME = "voidterm_settings";
    static final String KEY_MODEL_NAME = "whisper_model_name";
    static final String DEFAULT_MODEL = "ggml-base.bin";
    static final int REQUEST_MODEL_FILE = 1001;

    private final Activity activity;
    private final Runnable onBrowseAction;

    public SettingsDialog(Activity activity, Runnable onBrowseAction) {
        this.activity = activity;
        this.onBrowseAction = onBrowseAction;
    }

    public void show() {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String currentModel = prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL);

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);

        // Section: Whisper Model
        TextView sectionLabel = new TextView(activity);
        sectionLabel.setText("Whisper Model");
        sectionLabel.setTextSize(16);
        sectionLabel.setPadding(0, 24, 0, 8);
        layout.addView(sectionLabel);

        TextView currentLabel = new TextView(activity);
        currentLabel.setText("Current: " + currentModel);
        currentLabel.setTextSize(14);
        currentLabel.setPadding(0, 0, 0, 16);
        layout.addView(currentLabel);

        Button browseBtn = new Button(activity);
        browseBtn.setText("Browse...");
        browseBtn.setAllCaps(false);
        browseBtn.setTextSize(14);
        layout.addView(browseBtn);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("VoidTerm Settings")
                .setView(layout)
                .setPositiveButton("Close", null)
                .create();

        browseBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            activity.startActivityForResult(intent, REQUEST_MODEL_FILE);
            dialog.dismiss();
        });

        dialog.show();
    }
}
