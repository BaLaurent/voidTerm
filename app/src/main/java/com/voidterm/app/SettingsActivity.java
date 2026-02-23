package com.voidterm.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.voidterm.voice.DeviceProfiler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Full-screen settings activity with accordion-style collapsible sections.
 * Section headers use GameBoy 3D gradient buttons (convex=collapsed, concave=expanded).
 * Only one section is open at a time.
 */
public class SettingsActivity extends Activity {

    private static final String TAG = "SettingsActivity";
    public static final int RESULT_CUSTOMIZE_LAYOUT = 42;

    private static final int SECTION_MODEL = 0;
    private static final int SECTION_TRANSCRIPTION = 1;
    private static final int SECTION_INTERFACE = 2;
    private static final int SECTION_BACK_KEY = 3;
    private static final int SECTION_COUNT = 4;

    private SharedPreferences prefs;
    private InterfaceTheme theme;

    private ScrollView scrollView;
    private LinearLayout root;
    private final LinearLayout[] sectionBodies = new LinearLayout[SECTION_COUNT];
    private final View[] sectionHeaders = new View[SECTION_COUNT];
    private int expandedSection = SECTION_MODEL;

    // Text fields that need saving onPause
    private EditText promptField;
    private EditText macroField;

    // Guard against spinner listeners firing during programmatic setSelection()
    private boolean initializing = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(SettingsDialog.PREFS_NAME, MODE_PRIVATE);
        theme = InterfaceTheme.current(this);

        scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(theme.cross);
        scrollView.setFillViewport(true);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        buildTitle();

        buildSectionHeader(SECTION_MODEL, "Whisper Model", theme.primary);
        sectionBodies[SECTION_MODEL] = buildModelSection();
        root.addView(sectionBodies[SECTION_MODEL]);

        buildSectionHeader(SECTION_TRANSCRIPTION, "Transcription", theme.modifier);
        sectionBodies[SECTION_TRANSCRIPTION] = buildTranscriptionSection();
        root.addView(sectionBodies[SECTION_TRANSCRIPTION]);

        buildSectionHeader(SECTION_INTERFACE, "Interface", theme.modifier);
        sectionBodies[SECTION_INTERFACE] = buildInterfaceSection();
        root.addView(sectionBodies[SECTION_INTERFACE]);

        buildSectionHeader(SECTION_BACK_KEY, "Back Key", theme.modifier);
        sectionBodies[SECTION_BACK_KEY] = buildBackKeySection();
        root.addView(sectionBodies[SECTION_BACK_KEY]);

        // Initial accordion state: only first section visible
        for (int i = 0; i < SECTION_COUNT; i++) {
            sectionBodies[i].setVisibility(i == expandedSection ? View.VISIBLE : View.GONE);
        }
        updateHeaderDrawables();

        scrollView.addView(root);
        setContentView(scrollView);

        // Clear initializing flag after queued SelectionNotifier runnables
        root.post(() -> initializing = false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Save text fields
        if (promptField != null) {
            prefs.edit().putString(SettingsDialog.KEY_WHISPER_INITIAL_PROMPT,
                    promptField.getText().toString()).apply();
        }
        if (macroField != null) {
            prefs.edit().putString(SettingsDialog.KEY_BACK_MACRO,
                    macroField.getText().toString()).apply();
        }
    }

    // --- Title ---

    private void buildTitle() {
        TextView title = new TextView(this);
        title.setText("VoidTerm Settings");
        title.setTextColor(theme.active);
        title.setTextSize(20);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(8), 0, dp(16));
        root.addView(title);
    }

    // --- Accordion ---

    private void buildSectionHeader(int index, String label, int color) {
        TextView header = new TextView(this);
        header.setText(label);
        header.setTextColor(Color.WHITE);
        header.setTextSize(16);
        header.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(12));
        header.setTag(color);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        header.setLayoutParams(lp);

        header.setOnClickListener(v -> toggleSection(index));

        sectionHeaders[index] = header;
        root.addView(header);
    }

    private void toggleSection(int index) {
        if (index == expandedSection) return;

        sectionBodies[expandedSection].setVisibility(View.GONE);
        expandedSection = index;
        sectionBodies[expandedSection].setVisibility(View.VISIBLE);

        updateHeaderDrawables();

        // Scroll to the newly opened section
        sectionHeaders[index].post(() ->
                scrollView.smoothScrollTo(0, sectionHeaders[index].getTop()));
    }

    private void updateHeaderDrawables() {
        for (int i = 0; i < SECTION_COUNT; i++) {
            int color = (int) sectionHeaders[i].getTag();
            boolean expanded = (i == expandedSection);
            sectionHeaders[i].setBackground(makeSectionDrawable(color, expanded));
        }
    }

    private GradientDrawable makeSectionDrawable(int color, boolean expanded) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(8));
        gd.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);

        if (expanded) {
            // Concave: dark top → slightly lighter bottom
            gd.setColors(new int[]{
                    InterfaceTheme.darkenColor(color, 0.6f),
                    InterfaceTheme.darkenColor(color, 0.8f)});
            gd.setStroke(dp(2), InterfaceTheme.darkenColor(color, 0.4f));
        } else {
            // Convex: light top → darker bottom
            gd.setColors(new int[]{
                    InterfaceTheme.lightenColor(color, 1.4f),
                    InterfaceTheme.darkenColor(color, 0.85f)});
            gd.setStroke(dp(2), InterfaceTheme.darkenColor(color, 0.55f));
        }
        return gd;
    }

    // --- Section: Whisper Model ---

    private LinearLayout buildModelSection() {
        LinearLayout body = makeSectionBody();

        String currentModel = prefs.getString(SettingsDialog.KEY_MODEL_NAME,
                SettingsDialog.DEFAULT_MODEL);

        TextView modelLabel = new TextView(this);
        modelLabel.setText("Current: " + currentModel);
        modelLabel.setTextColor(Color.WHITE);
        modelLabel.setTextSize(14);
        modelLabel.setPadding(0, 0, 0, dp(12));
        body.addView(modelLabel);

        Button browseBtn = makeActionButton("Browse...");
        browseBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, SettingsDialog.REQUEST_MODEL_FILE);
        });
        body.addView(browseBtn);

        return body;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SettingsDialog.REQUEST_MODEL_FILE
                && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                onModelFileSelected(uri);
            }
        }
    }

    private void onModelFileSelected(Uri uri) {
        String filename = getFileNameFromUri(uri);
        if (filename == null) {
            Log.e(TAG, "Could not resolve filename from URI");
            return;
        }

        File modelsDir = new File(getFilesDir(), "models");
        if (!modelsDir.exists()) modelsDir.mkdirs();
        File destFile = new File(modelsDir, filename);

        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(destFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
            Log.i(TAG, "Model file copied: " + destFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy model file", e);
            return;
        }

        prefs.edit()
                .putString(SettingsDialog.KEY_MODEL_NAME, filename)
                .putBoolean(SettingsDialog.KEY_MODEL_RELOAD_REQUESTED, true)
                .apply();

        // Update the label in the model section
        if (sectionBodies[SECTION_MODEL] != null
                && sectionBodies[SECTION_MODEL].getChildCount() > 0) {
            View first = sectionBodies[SECTION_MODEL].getChildAt(0);
            if (first instanceof TextView) {
                ((TextView) first).setText("Current: " + filename);
            }
        }
    }

    private String getFileNameFromUri(Uri uri) {
        String name = null;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) name = cursor.getString(index);
            }
        }
        return name;
    }

    // --- Section: Transcription ---

    private LinearLayout buildTranscriptionSection() {
        LinearLayout body = makeSectionBody();

        // Auto-tune tier info
        String tierName = prefs.getString(DeviceProfiler.KEY_AUTOTUNE_TIER, null);
        if (tierName != null) {
            long benchmarkMs = prefs.getLong(DeviceProfiler.KEY_AUTOTUNE_BENCHMARK_MS, 0);
            TextView tierInfo = new TextView(this);
            tierInfo.setText("Performance: " + tierName.charAt(0)
                    + tierName.substring(1).toLowerCase() + " (" + benchmarkMs + "ms)");
            tierInfo.setTextColor(0xFFCCCCCC);
            tierInfo.setTextSize(13);
            tierInfo.setPadding(0, 0, 0, dp(8));
            body.addView(tierInfo);
        }

        // Reset to Auto button
        Button resetAutoBtn = makeActionButton("Reset to Auto");
        resetAutoBtn.setOnClickListener(v -> {
            DeviceProfiler.resetToAuto(prefs);
            prefs.edit().putBoolean(SettingsDialog.KEY_MODEL_RELOAD_REQUESTED, true).apply();
            finish();
        });
        body.addView(resetAutoBtn);

        // Language spinner
        body.addView(makeLabel("Language"));
        String currentLang = prefs.getString(SettingsDialog.KEY_WHISPER_LANGUAGE, "en");
        Spinner langSpinner = makeSpinner(SettingsDialog.LANGUAGE_LABELS);
        int langIndex = findIndex(SettingsDialog.LANGUAGE_CODES, currentLang);
        langSpinner.setSelection(langIndex);
        body.addView(langSpinner);

        // Translate checkbox
        CheckBox translateToggle = makeCheckBox("Translate to English",
                prefs.getBoolean(SettingsDialog.KEY_WHISPER_TRANSLATE, false));
        translateToggle.setEnabled(!"en".equals(currentLang));
        translateToggle.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(SettingsDialog.KEY_WHISPER_TRANSLATE, checked).apply());
        body.addView(translateToggle);

        langSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putString(SettingsDialog.KEY_WHISPER_LANGUAGE,
                        SettingsDialog.LANGUAGE_CODES[pos]).apply();
                translateToggle.setEnabled(!"en".equals(SettingsDialog.LANGUAGE_CODES[pos]));
            }
        });

        // Initial prompt
        body.addView(makeLabel("Initial prompt"));
        promptField = new EditText(this);
        promptField.setHint("Domain terms, names...");
        promptField.setTextColor(Color.WHITE);
        promptField.setHintTextColor(0xAA999999);
        promptField.setTextSize(14);
        promptField.setSingleLine(true);
        promptField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});
        promptField.setText(prefs.getString(SettingsDialog.KEY_WHISPER_INITIAL_PROMPT, ""));
        body.addView(promptField);

        // Streaming transcription toggle
        CheckBox streamingToggle = makeCheckBox("Streaming transcription",
                prefs.getBoolean(SettingsDialog.KEY_WHISPER_STREAMING, false));
        body.addView(streamingToggle);

        TextView streamingWarning = new TextView(this);
        streamingWarning.setText("\u26A0 Streaming sends text directly to the terminal without review. You cannot edit or cancel before submission.");
        streamingWarning.setTextSize(12);
        streamingWarning.setTextColor(0xFFFF9800);
        streamingWarning.setPadding(0, 0, 0, dp(8));
        streamingWarning.setVisibility(
                prefs.getBoolean(SettingsDialog.KEY_WHISPER_STREAMING, false)
                        ? View.VISIBLE : View.GONE);
        body.addView(streamingWarning);

        streamingToggle.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(SettingsDialog.KEY_WHISPER_STREAMING, checked).apply();
            streamingWarning.setVisibility(checked ? View.VISIBLE : View.GONE);
        });

        // Audio preprocessing toggle
        CheckBox preprocessingToggle = makeCheckBox(
                "Voice preprocessing (emphasis + normalize)",
                prefs.getBoolean(SettingsDialog.KEY_AUDIO_PREPROCESSING, true));
        body.addView(preprocessingToggle);

        // Audio Tuning button
        Button tuningBtn = makeActionButton("Audio Tuning...");
        tuningBtn.setEnabled(preprocessingToggle.isChecked());
        tuningBtn.setOnClickListener(v -> new AudioDebugDialog(this).show());
        body.addView(tuningBtn);

        preprocessingToggle.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(SettingsDialog.KEY_AUDIO_PREPROCESSING, checked).apply();
            tuningBtn.setEnabled(checked);
        });

        // --- Advanced divider ---
        body.addView(makeDivider());
        body.addView(makeSubheading("Advanced"));

        // Temperature spinner
        body.addView(makeLabel("Temperature"));
        float currentTemp = prefs.getFloat(SettingsDialog.KEY_WHISPER_TEMPERATURE, 0.0f);
        Spinner tempSpinner = makeSpinner(SettingsDialog.TEMPERATURE_LABELS);
        int tempIndex = 0;
        for (int i = 0; i < SettingsDialog.TEMPERATURE_VALUES.length; i++) {
            if (Math.abs(SettingsDialog.TEMPERATURE_VALUES[i] - currentTemp) < 0.01f) {
                tempIndex = i;
                break;
            }
        }
        tempSpinner.setSelection(tempIndex);
        tempSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putFloat(SettingsDialog.KEY_WHISPER_TEMPERATURE,
                        SettingsDialog.TEMPERATURE_VALUES[pos]).apply();
            }
        });
        body.addView(tempSpinner);

        // Beam search toggle + beam size spinner
        CheckBox beamToggle = makeCheckBox("Use beam search",
                prefs.getBoolean(SettingsDialog.KEY_WHISPER_BEAM_SEARCH, false));
        body.addView(beamToggle);

        LinearLayout beamSizeRow = new LinearLayout(this);
        beamSizeRow.setOrientation(LinearLayout.HORIZONTAL);
        beamSizeRow.setGravity(Gravity.CENTER_VERTICAL);
        beamSizeRow.setVisibility(
                prefs.getBoolean(SettingsDialog.KEY_WHISPER_BEAM_SEARCH, false)
                        ? View.VISIBLE : View.GONE);

        TextView beamSizeLabel = new TextView(this);
        beamSizeLabel.setText("Beam size: ");
        beamSizeLabel.setTextColor(Color.WHITE);
        beamSizeLabel.setTextSize(14);
        beamSizeRow.addView(beamSizeLabel);

        String[] beamOptions = {"2", "3", "4", "5", "6", "7", "8"};
        Spinner beamSpinner = makeSpinner(beamOptions);
        int currentBeam = prefs.getInt(SettingsDialog.KEY_WHISPER_BEAM_SIZE, 5);
        beamSpinner.setSelection(Math.max(0, Math.min(currentBeam - 2, beamOptions.length - 1)));
        beamSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putInt(SettingsDialog.KEY_WHISPER_BEAM_SIZE, pos + 2).apply();
                if (!initializing) {
                    DeviceProfiler.markUserOverride(prefs, SettingsDialog.KEY_WHISPER_BEAM_SIZE);
                }
            }
        });
        beamSizeRow.addView(beamSpinner);
        body.addView(beamSizeRow);

        beamToggle.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(SettingsDialog.KEY_WHISPER_BEAM_SEARCH, checked).apply();
            DeviceProfiler.markUserOverride(prefs, SettingsDialog.KEY_WHISPER_BEAM_SEARCH);
            beamSizeRow.setVisibility(checked ? View.VISIBLE : View.GONE);
        });

        // Suppress non-speech tokens
        CheckBox suppressToggle = makeCheckBox("Suppress non-speech tokens",
                prefs.getBoolean(SettingsDialog.KEY_WHISPER_SUPPRESS_NON_SPEECH, false));
        suppressToggle.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(SettingsDialog.KEY_WHISPER_SUPPRESS_NON_SPEECH, checked).apply();
            DeviceProfiler.markUserOverride(prefs, SettingsDialog.KEY_WHISPER_SUPPRESS_NON_SPEECH);
        });
        body.addView(suppressToggle);

        // Thread count override
        body.addView(makeLabel("Threads"));
        String[] threadOptions = {"Auto", "1", "2", "3", "4", "5", "6", "7", "8"};
        Spinner threadSpinner = makeSpinner(threadOptions);
        int currentThreads = prefs.getInt(SettingsDialog.KEY_WHISPER_THREAD_OVERRIDE, 0);
        threadSpinner.setSelection(
                Math.max(0, Math.min(currentThreads, threadOptions.length - 1)));
        threadSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putInt(SettingsDialog.KEY_WHISPER_THREAD_OVERRIDE, pos).apply();
            }
        });
        body.addView(threadSpinner);

        // Proportional audio context
        CheckBox proportionalToggle = makeCheckBox(
                "Proportional audio context (experimental)",
                prefs.getBoolean(SettingsDialog.KEY_WHISPER_PROPORTIONAL_CONTEXT, false));
        proportionalToggle.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(SettingsDialog.KEY_WHISPER_PROPORTIONAL_CONTEXT,
                    checked).apply();
            DeviceProfiler.markUserOverride(prefs,
                    SettingsDialog.KEY_WHISPER_PROPORTIONAL_CONTEXT);
        });
        body.addView(proportionalToggle);

        return body;
    }

    // --- Section: Interface ---

    private LinearLayout buildInterfaceSection() {
        LinearLayout body = makeSectionBody();

        // Theme spinner
        body.addView(makeLabel("Theme"));
        InterfaceTheme[] themes = InterfaceTheme.values();
        String[] themeLabels = new String[themes.length];
        for (int i = 0; i < themes.length; i++) themeLabels[i] = themes[i].label;

        Spinner themeSpinner = makeSpinner(themeLabels);
        themeSpinner.setSelection(theme.ordinal());
        themeSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                InterfaceTheme selected = themes[pos];
                if (selected != theme) {
                    InterfaceTheme.save(SettingsActivity.this, selected);
                    theme = selected;
                    refreshThemeColors();
                }
            }
        });
        body.addView(themeSpinner);

        // Panel mode spinner
        body.addView(makeLabel("Panel mode"));
        String[] panelModeLabels = {"GameBoy Panel", "Compact Panel", "Fullscreen (toolbar only)"};
        String[] panelModeValues = {SettingsDialog.PANEL_GAMEBOY, SettingsDialog.PANEL_COMPACT,
                SettingsDialog.PANEL_FULLSCREEN};
        String currentPanelMode = SettingsDialog.migratePanelMode(prefs);

        Spinner panelModeSpinner = makeSpinner(panelModeLabels);
        int panelIndex = findIndex(panelModeValues, currentPanelMode);
        panelModeSpinner.setSelection(panelIndex);
        body.addView(panelModeSpinner);

        boolean isFullscreen = SettingsDialog.PANEL_FULLSCREEN.equals(currentPanelMode);
        boolean isGameboy = SettingsDialog.PANEL_GAMEBOY.equals(currentPanelMode);

        // Compact toolbar toggle
        CheckBox toolbarToggle = makeCheckBox("Compact toolbar above keyboard",
                prefs.getBoolean(SettingsDialog.KEY_COMPACT_TOOLBAR, true));
        toolbarToggle.setEnabled(!isFullscreen);
        toolbarToggle.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(SettingsDialog.KEY_COMPACT_TOOLBAR, checked).apply());
        body.addView(toolbarToggle);

        // Customize Layout button
        Button customLayoutBtn = makeActionButton("Customize Layout");
        customLayoutBtn.setEnabled(isGameboy);
        customLayoutBtn.setOnClickListener(v -> {
            setResult(RESULT_CUSTOMIZE_LAYOUT);
            finish();
        });
        body.addView(customLayoutBtn);

        panelModeSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putString(SettingsDialog.KEY_PANEL_MODE,
                        panelModeValues[pos]).apply();
                toolbarToggle.setEnabled(
                        !SettingsDialog.PANEL_FULLSCREEN.equals(panelModeValues[pos]));
                customLayoutBtn.setEnabled(
                        SettingsDialog.PANEL_GAMEBOY.equals(panelModeValues[pos]));
            }
        });

        // Tap to toggle keyboard
        CheckBox tapToggle = makeCheckBox("Tap terminal to toggle keyboard",
                prefs.getBoolean(SettingsDialog.KEY_TAP_TOGGLE_KEYBOARD, true));
        tapToggle.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(SettingsDialog.KEY_TAP_TOGGLE_KEYBOARD, checked).apply());
        body.addView(tapToggle);

        // Haptic feedback
        CheckBox hapticToggle = makeCheckBox("Haptic feedback",
                prefs.getBoolean(SettingsDialog.KEY_HAPTIC_FEEDBACK, true));
        hapticToggle.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(SettingsDialog.KEY_HAPTIC_FEEDBACK, checked).apply());
        body.addView(hapticToggle);

        return body;
    }

    // --- Section: Back Key ---

    private LinearLayout buildBackKeySection() {
        LinearLayout body = makeSectionBody();

        String[] backOptions = {"Escape", "Toggle Keyboard", "Macro"};
        String[] backValues = {SettingsDialog.BACK_ESCAPE,
                SettingsDialog.BACK_TOGGLE_KEYBOARD, SettingsDialog.BACK_MACRO};
        String currentBack = prefs.getString(SettingsDialog.KEY_BACK_BEHAVIOR,
                SettingsDialog.BACK_ESCAPE);

        body.addView(makeLabel("Behavior"));
        Spinner backSpinner = makeSpinner(backOptions);
        int selectedIndex = findIndex(backValues, currentBack);
        backSpinner.setSelection(selectedIndex);
        body.addView(backSpinner);

        macroField = new EditText(this);
        macroField.setHint("Macro command (e.g. {ctrl+c})");
        macroField.setTextColor(Color.WHITE);
        macroField.setHintTextColor(0xAA999999);
        macroField.setTextSize(14);
        macroField.setSingleLine(true);
        macroField.setText(prefs.getString(SettingsDialog.KEY_BACK_MACRO, ""));
        macroField.setVisibility(
                SettingsDialog.BACK_MACRO.equals(currentBack) ? View.VISIBLE : View.GONE);
        body.addView(macroField);

        backSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putString(SettingsDialog.KEY_BACK_BEHAVIOR,
                        backValues[pos]).apply();
                macroField.setVisibility(pos == 2 ? View.VISIBLE : View.GONE);
            }
        });

        return body;
    }

    // --- Theme live-update ---

    private void refreshThemeColors() {
        scrollView.setBackgroundColor(theme.cross);

        // Update title color
        if (root.getChildCount() > 0) {
            View titleView = root.getChildAt(0);
            if (titleView instanceof TextView) {
                ((TextView) titleView).setTextColor(theme.active);
            }
        }

        // Update header colors: model=primary, rest=modifier
        sectionHeaders[SECTION_MODEL].setTag(theme.primary);
        sectionHeaders[SECTION_TRANSCRIPTION].setTag(theme.modifier);
        sectionHeaders[SECTION_INTERFACE].setTag(theme.modifier);
        sectionHeaders[SECTION_BACK_KEY].setTag(theme.modifier);
        updateHeaderDrawables();

        // Update section body backgrounds
        int bodyBg = InterfaceTheme.darkenColor(theme.cross, 0.9f);
        for (LinearLayout body : sectionBodies) {
            if (body != null) body.setBackgroundColor(bodyBg);
        }
    }

    // --- Widget factories ---

    private LinearLayout makeSectionBody() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(12), dp(16), dp(16));
        body.setBackgroundColor(InterfaceTheme.darkenColor(theme.cross, 0.9f));
        return body;
    }

    private TextView makeLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(0xFFCCCCCC);
        label.setTextSize(13);
        label.setPadding(0, dp(8), 0, dp(4));
        return label;
    }

    private TextView makeSubheading(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(theme.active);
        label.setTextSize(14);
        label.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        label.setPadding(0, dp(4), 0, dp(8));
        return label;
    }

    private View makeDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(theme.dpad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(4);
        divider.setLayoutParams(lp);
        return divider;
    }

    private CheckBox makeCheckBox(String text, boolean checked) {
        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        cb.setTextColor(Color.WHITE);
        cb.setTextSize(14);
        cb.setChecked(checked);
        return cb;
    }

    private Spinner makeSpinner(String[] items) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                if (view instanceof TextView) {
                    ((TextView) view).setTextColor(Color.WHITE);
                }
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                if (view instanceof TextView) {
                    ((TextView) view).setTextColor(Color.WHITE);
                    view.setBackgroundColor(theme.cross);
                }
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    private Button makeActionButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setAllCaps(false);
        btn.setTextSize(14);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setBackground(makeSectionDrawable(theme.primary, false));
        btn.setPadding(dp(16), dp(8), dp(16), dp(8));
        btn.setStateListAnimator(null);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        btn.setLayoutParams(lp);
        return btn;
    }

    // --- Helpers ---

    private int dp(int value) {
        return PanelUtils.dp(this, value);
    }

    private static int findIndex(String[] array, String value) {
        for (int i = 0; i < array.length; i++) {
            if (array[i].equals(value)) return i;
        }
        return 0;
    }

    /** Minimal OnItemSelectedListener that only requires onItemSelected. */
    private static abstract class SimpleItemSelectedListener
            implements AdapterView.OnItemSelectedListener {
        @Override
        public void onNothingSelected(AdapterView<?> parent) {}
    }
}
