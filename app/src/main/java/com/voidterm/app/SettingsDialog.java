package com.voidterm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * Settings dialog for VoidTerm. Displays current whisper model name
 * and provides a file picker to select a different model.
 */
public class SettingsDialog {

    static final String PREFS_NAME = "voidterm_settings";
    static final String KEY_MODEL_NAME = "whisper_model_name";
    static final String KEY_COMPACT_TOOLBAR = "compact_toolbar_enabled";
    static final String KEY_TAP_TOGGLE_KEYBOARD = "tap_toggle_keyboard";
    static final String KEY_HAPTIC_FEEDBACK = "haptic_feedback";
    static final String KEY_USE_GPU = "use_gpu";
    static final String KEY_BACK_BEHAVIOR = "back_key_behavior";
    static final String KEY_BACK_MACRO = "back_key_macro";
    static final String BACK_ESCAPE = "escape";
    static final String BACK_TOGGLE_KEYBOARD = "toggle_keyboard";
    static final String BACK_MACRO = "macro";
    static final String DEFAULT_MODEL = "ggml-base.bin";
    static final int REQUEST_MODEL_FILE = 1001;

    private final Activity activity;
    private final Runnable onBrowseAction;
    private final Runnable onLayoutEditAction;

    public SettingsDialog(Activity activity, Runnable onBrowseAction, Runnable onLayoutEditAction) {
        this.activity = activity;
        this.onBrowseAction = onBrowseAction;
        this.onLayoutEditAction = onLayoutEditAction;
    }

    static boolean isHapticEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_HAPTIC_FEEDBACK, true);
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

        // Section: Interface
        TextView interfaceLabel = new TextView(activity);
        interfaceLabel.setText("Interface");
        interfaceLabel.setTextSize(16);
        interfaceLabel.setPadding(0, 32, 0, 8);
        layout.addView(interfaceLabel);

        CheckBox toolbarToggle = new CheckBox(activity);
        toolbarToggle.setText("Compact toolbar above keyboard");
        toolbarToggle.setTextSize(14);
        toolbarToggle.setChecked(prefs.getBoolean(KEY_COMPACT_TOOLBAR, true));
        toolbarToggle.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(KEY_COMPACT_TOOLBAR, checked).apply());
        layout.addView(toolbarToggle);

        CheckBox tapToggle = new CheckBox(activity);
        tapToggle.setText("Tap terminal to toggle keyboard");
        tapToggle.setTextSize(14);
        tapToggle.setChecked(prefs.getBoolean(KEY_TAP_TOGGLE_KEYBOARD, true));
        tapToggle.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(KEY_TAP_TOGGLE_KEYBOARD, checked).apply());
        layout.addView(tapToggle);

        CheckBox hapticToggle = new CheckBox(activity);
        hapticToggle.setText("Haptic feedback");
        hapticToggle.setTextSize(14);
        hapticToggle.setChecked(prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true));
        hapticToggle.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(KEY_HAPTIC_FEEDBACK, checked).apply());
        layout.addView(hapticToggle);

        CheckBox gpuToggle = new CheckBox(activity);
        gpuToggle.setText("Use GPU acceleration");
        gpuToggle.setTextSize(14);
        gpuToggle.setChecked(prefs.getBoolean(KEY_USE_GPU, false));
        gpuToggle.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(KEY_USE_GPU, checked).apply());
        layout.addView(gpuToggle);

        Button customLayoutBtn = new Button(activity);
        customLayoutBtn.setText("Customize Layout");
        customLayoutBtn.setAllCaps(false);
        customLayoutBtn.setTextSize(14);
        layout.addView(customLayoutBtn);

        // Section: Back Key
        TextView backLabel = new TextView(activity);
        backLabel.setText("Back Key");
        backLabel.setTextSize(16);
        backLabel.setPadding(0, 32, 0, 8);
        layout.addView(backLabel);

        String[] backOptions = {"Escape", "Toggle Keyboard", "Macro"};
        String[] backValues = {BACK_ESCAPE, BACK_TOGGLE_KEYBOARD, BACK_MACRO};
        String currentBack = prefs.getString(KEY_BACK_BEHAVIOR, BACK_ESCAPE);

        Spinner backSpinner = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, backOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        backSpinner.setAdapter(adapter);

        int selectedIndex = 0;
        for (int i = 0; i < backValues.length; i++) {
            if (backValues[i].equals(currentBack)) { selectedIndex = i; break; }
        }
        backSpinner.setSelection(selectedIndex);

        EditText macroField = new EditText(activity);
        macroField.setHint("Macro command (e.g. {ctrl+c})");
        macroField.setTextSize(14);
        macroField.setSingleLine(true);
        macroField.setText(prefs.getString(KEY_BACK_MACRO, ""));
        macroField.setVisibility(BACK_MACRO.equals(currentBack) ? View.VISIBLE : View.GONE);

        backSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                prefs.edit().putString(KEY_BACK_BEHAVIOR, backValues[pos]).apply();
                macroField.setVisibility(pos == 2 ? View.VISIBLE : View.GONE);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        layout.addView(backSpinner);
        layout.addView(macroField);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("VoidTerm Settings")
                .setView(layout)
                .setPositiveButton("Close", (d, w) -> {
                    String macroText = macroField.getText().toString();
                    prefs.edit().putString(KEY_BACK_MACRO, macroText).apply();
                })
                .create();

        browseBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            activity.startActivityForResult(intent, REQUEST_MODEL_FILE);
            dialog.dismiss();
        });

        customLayoutBtn.setOnClickListener(v -> {
            dialog.dismiss();
            if (onLayoutEditAction != null) onLayoutEditAction.run();
        });

        dialog.show();
    }
}
