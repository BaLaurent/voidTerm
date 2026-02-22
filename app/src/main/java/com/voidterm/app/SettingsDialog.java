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
import android.widget.ScrollView;
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
    static final String KEY_WHISPER_LANGUAGE = "whisper_language";
    static final String KEY_WHISPER_TRANSLATE = "whisper_translate";
    static final String KEY_WHISPER_INITIAL_PROMPT = "whisper_initial_prompt";
    static final String KEY_WHISPER_TEMPERATURE = "whisper_temperature";
    static final String KEY_WHISPER_BEAM_SEARCH = "whisper_beam_search";
    static final String KEY_WHISPER_BEAM_SIZE = "whisper_beam_size";
    static final String KEY_WHISPER_THREAD_OVERRIDE = "whisper_thread_override";
    static final String KEY_WHISPER_SUPPRESS_NON_SPEECH = "whisper_suppress_non_speech";
    static final String BACK_ESCAPE = "escape";
    static final String BACK_TOGGLE_KEYBOARD = "toggle_keyboard";
    static final String BACK_MACRO = "macro";
    static final String DEFAULT_MODEL = "ggml-base.bin";
    static final int REQUEST_MODEL_FILE = 1001;

    private static final String[] LANGUAGE_LABELS = {
        "Auto-detect", "English", "Chinese", "Spanish", "Hindi", "Arabic",
        "French", "Bengali", "Portuguese", "Russian", "Japanese", "German",
        "Korean", "Indonesian", "Turkish", "Vietnamese", "Italian", "Thai",
        "Polish", "Dutch", "Ukrainian", "Romanian", "Greek", "Czech",
        "Swedish", "Malay"
    };
    private static final String[] LANGUAGE_CODES = {
        "auto", "en", "zh", "es", "hi", "ar",
        "fr", "bn", "pt", "ru", "ja", "de",
        "ko", "id", "tr", "vi", "it", "th",
        "pl", "nl", "uk", "ro", "el", "cs",
        "sv", "ms"
    };
    private static final String[] TEMPERATURE_LABELS = {
        "0.0 (Precise)", "0.2", "0.4", "0.6", "0.8", "1.0 (Creative)"
    };
    private static final float[] TEMPERATURE_VALUES = {
        0.0f, 0.2f, 0.4f, 0.6f, 0.8f, 1.0f
    };

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

        // Section: Transcription
        TextView transLabel = new TextView(activity);
        transLabel.setText("Transcription");
        transLabel.setTextSize(16);
        transLabel.setPadding(0, 32, 0, 8);
        layout.addView(transLabel);

        // Language spinner
        String currentLang = prefs.getString(KEY_WHISPER_LANGUAGE, "en");
        Spinner langSpinner = new Spinner(activity);
        ArrayAdapter<String> langAdapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, LANGUAGE_LABELS);
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        langSpinner.setAdapter(langAdapter);
        int langIndex = 0;
        for (int i = 0; i < LANGUAGE_CODES.length; i++) {
            if (LANGUAGE_CODES[i].equals(currentLang)) { langIndex = i; break; }
        }
        langSpinner.setSelection(langIndex);
        layout.addView(langSpinner);

        // Translate checkbox
        CheckBox translateToggle = new CheckBox(activity);
        translateToggle.setText("Translate to English");
        translateToggle.setTextSize(14);
        translateToggle.setChecked(prefs.getBoolean(KEY_WHISPER_TRANSLATE, false));
        translateToggle.setEnabled(!"en".equals(currentLang));
        translateToggle.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(KEY_WHISPER_TRANSLATE, checked).apply());
        layout.addView(translateToggle);

        langSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                prefs.edit().putString(KEY_WHISPER_LANGUAGE, LANGUAGE_CODES[pos]).apply();
                translateToggle.setEnabled(!"en".equals(LANGUAGE_CODES[pos]));
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Initial prompt
        EditText promptField = new EditText(activity);
        promptField.setHint("Domain terms, names...");
        promptField.setTextSize(14);
        promptField.setSingleLine(true);
        promptField.setText(prefs.getString(KEY_WHISPER_INITIAL_PROMPT, ""));
        layout.addView(promptField);

        // Advanced section (collapsed by default)
        LinearLayout advancedContainer = new LinearLayout(activity);
        advancedContainer.setOrientation(LinearLayout.VERTICAL);
        advancedContainer.setVisibility(View.GONE);

        Button advancedBtn = new Button(activity);
        advancedBtn.setText("Advanced...");
        advancedBtn.setAllCaps(false);
        advancedBtn.setTextSize(14);
        advancedBtn.setOnClickListener(v -> {
            if (advancedContainer.getVisibility() == View.GONE) {
                advancedContainer.setVisibility(View.VISIBLE);
                advancedBtn.setText("Advanced \u25B2");
            } else {
                advancedContainer.setVisibility(View.GONE);
                advancedBtn.setText("Advanced...");
            }
        });
        layout.addView(advancedBtn);

        // Temperature spinner
        float currentTemp = prefs.getFloat(KEY_WHISPER_TEMPERATURE, 0.0f);
        Spinner tempSpinner = new Spinner(activity);
        ArrayAdapter<String> tempAdapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, TEMPERATURE_LABELS);
        tempAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        tempSpinner.setAdapter(tempAdapter);
        int tempIndex = 0;
        for (int i = 0; i < TEMPERATURE_VALUES.length; i++) {
            if (Math.abs(TEMPERATURE_VALUES[i] - currentTemp) < 0.01f) { tempIndex = i; break; }
        }
        tempSpinner.setSelection(tempIndex);
        tempSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                prefs.edit().putFloat(KEY_WHISPER_TEMPERATURE, TEMPERATURE_VALUES[pos]).apply();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        advancedContainer.addView(tempSpinner);

        // Beam search checkbox + beam size spinner
        LinearLayout beamSizeRow = new LinearLayout(activity);
        beamSizeRow.setOrientation(LinearLayout.HORIZONTAL);
        beamSizeRow.setVisibility(prefs.getBoolean(KEY_WHISPER_BEAM_SEARCH, false) ? View.VISIBLE : View.GONE);

        TextView beamSizeLabel = new TextView(activity);
        beamSizeLabel.setText("Beam size: ");
        beamSizeLabel.setTextSize(14);
        beamSizeRow.addView(beamSizeLabel);

        String[] beamOptions = {"2", "3", "4", "5", "6", "7", "8"};
        Spinner beamSpinner = new Spinner(activity);
        ArrayAdapter<String> beamAdapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, beamOptions);
        beamAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        beamSpinner.setAdapter(beamAdapter);
        int currentBeam = prefs.getInt(KEY_WHISPER_BEAM_SIZE, 5);
        beamSpinner.setSelection(Math.max(0, Math.min(currentBeam - 2, beamOptions.length - 1)));
        beamSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                prefs.edit().putInt(KEY_WHISPER_BEAM_SIZE, pos + 2).apply();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        beamSizeRow.addView(beamSpinner);

        CheckBox beamToggle = new CheckBox(activity);
        beamToggle.setText("Use beam search");
        beamToggle.setTextSize(14);
        beamToggle.setChecked(prefs.getBoolean(KEY_WHISPER_BEAM_SEARCH, false));
        beamToggle.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(KEY_WHISPER_BEAM_SEARCH, checked).apply();
            beamSizeRow.setVisibility(checked ? View.VISIBLE : View.GONE);
        });
        advancedContainer.addView(beamToggle);
        advancedContainer.addView(beamSizeRow);

        // Suppress non-speech tokens
        CheckBox suppressToggle = new CheckBox(activity);
        suppressToggle.setText("Suppress non-speech tokens");
        suppressToggle.setTextSize(14);
        suppressToggle.setChecked(prefs.getBoolean(KEY_WHISPER_SUPPRESS_NON_SPEECH, false));
        suppressToggle.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(KEY_WHISPER_SUPPRESS_NON_SPEECH, checked).apply());
        advancedContainer.addView(suppressToggle);

        // Thread count override
        String[] threadOptions = {"Auto", "1", "2", "3", "4", "5", "6", "7", "8"};
        Spinner threadSpinner = new Spinner(activity);
        ArrayAdapter<String> threadAdapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, threadOptions);
        threadAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        threadSpinner.setAdapter(threadAdapter);
        int currentThreads = prefs.getInt(KEY_WHISPER_THREAD_OVERRIDE, 0);
        threadSpinner.setSelection(Math.max(0, Math.min(currentThreads, threadOptions.length - 1)));
        threadSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                prefs.edit().putInt(KEY_WHISPER_THREAD_OVERRIDE, pos).apply();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        LinearLayout threadRow = new LinearLayout(activity);
        threadRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView threadLabel = new TextView(activity);
        threadLabel.setText("Threads: ");
        threadLabel.setTextSize(14);
        threadRow.addView(threadLabel);
        threadRow.addView(threadSpinner);
        advancedContainer.addView(threadRow);

        layout.addView(advancedContainer);

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

        ScrollView scrollView = new ScrollView(activity);
        scrollView.addView(layout);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("VoidTerm Settings")
                .setView(scrollView)
                .setPositiveButton("Close", (d, w) -> {
                    String macroText = macroField.getText().toString();
                    String promptText = promptField.getText().toString();
                    prefs.edit()
                            .putString(KEY_BACK_MACRO, macroText)
                            .putString(KEY_WHISPER_INITIAL_PROMPT, promptText)
                            .apply();
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
