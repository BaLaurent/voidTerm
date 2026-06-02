package com.voidterm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

import androidx.core.content.ContextCompat;

import com.voidterm.voice.DeviceProfiler;
import com.voidterm.voice.ParakeetModelManager;
import com.voidterm.voice.ParakeetQuantization;

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
    private static final int SECTION_VOLUME_KEYS = 4;
    private static final int SECTION_COUNT = 5;

    private SharedPreferences prefs;
    private InterfaceTheme theme;
    // Derived colors from theme — recomputed on theme change
    private int surfaceColor;   // main background
    private int bodyColor;      // section body background
    private int textColor;      // primary text
    private int mutedColor;     // secondary/label text
    private int hintColor;      // placeholder text

    private ScrollView scrollView;
    private LinearLayout root;
    private final LinearLayout[] sectionBodies = new LinearLayout[SECTION_COUNT];
    private final View[] sectionHeaders = new View[SECTION_COUNT];
    private static final String KEY_EXPANDED_SECTION = "expanded_section";
    private int expandedSection = SECTION_MODEL;

    // Shared option sets for key-gesture rows.
    private static final String[] GESTURE_VALUES_WITH_DEFAULT = {
            SettingsDialog.VOLUME_DEFAULT, SettingsDialog.BACK_ESCAPE,
            SettingsDialog.BACK_TOGGLE_KEYBOARD, SettingsDialog.BACK_MACRO,
            SettingsDialog.BACK_VOICE};
    private static final String[] VOLUME_SINGLE_LABELS = {
            "Default (system volume)", "Escape", "Toggle Keyboard", "Macro", "Voice Input"};
    private static final String[] NONEABLE_LABELS = {
            "None", "Escape", "Toggle Keyboard", "Macro", "Voice Input"};
    private static final String[] BACK_SINGLE_LABELS = {
            "Escape", "Toggle Keyboard", "Macro", "Voice Input"};
    private static final String[] BACK_SINGLE_VALUES = {
            SettingsDialog.BACK_ESCAPE, SettingsDialog.BACK_TOGGLE_KEYBOARD,
            SettingsDialog.BACK_MACRO, SettingsDialog.BACK_VOICE};

    // Text fields that need saving onPause
    private EditText promptField;

    // Whisper-only transcription controls (hidden when Parakeet selected)
    private LinearLayout whisperTranscriptionControls;

    // Whisper catalog view (model section)
    private WhisperCatalogView whisperCatalogView;

    // Parakeet quantization selector (model section) — updated by the download broadcast receiver
    private ParakeetQuantizationView parakeetQuantizationView;
    private BroadcastReceiver downloadReceiver;

    // Guard against spinner listeners firing during programmatic setSelection()
    private boolean initializing = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(SettingsDialog.PREFS_NAME, MODE_PRIVATE);
        theme = InterfaceTheme.current(this);
        computeDerivedColors();

        if (savedInstanceState != null) {
            expandedSection = savedInstanceState.getInt(KEY_EXPANDED_SECTION, SECTION_MODEL);
        }

        scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(surfaceColor);
        scrollView.setFillViewport(true);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        buildTitle();

        buildSectionHeader(SECTION_MODEL, "Voice Engine", theme.primary);
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

        buildSectionHeader(SECTION_VOLUME_KEYS, "Volume Keys", theme.modifier);
        sectionBodies[SECTION_VOLUME_KEYS] = buildVolumeKeysSection();
        root.addView(sectionBodies[SECTION_VOLUME_KEYS]);

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
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_EXPANDED_SECTION, expandedSection);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Observe the background download while this screen is visible.
        IntentFilter filter = new IntentFilter();
        filter.addAction(ModelDownloadService.BROADCAST_PROGRESS);
        filter.addAction(ModelDownloadService.BROADCAST_COMPLETE);
        filter.addAction(ModelDownloadService.BROADCAST_ERROR);
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onDownloadBroadcast(intent);
            }
        };
        ContextCompat.registerReceiver(this, downloadReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        // Re-sync in case the download state changed while paused.
        if (ModelDownloadService.isRunning()) {
            String last = ModelDownloadService.lastProgressText();
            String jobId = ModelDownloadService.runningJobId();
            if (jobId != null) {
                if (com.voidterm.voice.WhisperModelCatalog.byFileName(jobId) != null && whisperCatalogView != null) {
                    whisperCatalogView.onProgress(jobId, last != null ? last : "Downloading…");
                } else if (ParakeetQuantization.byId(jobId) != null
                        && parakeetQuantizationView != null) {
                    parakeetQuantizationView.onProgress(jobId, last != null ? last : "Downloading…");
                }
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (downloadReceiver != null) {
            unregisterReceiver(downloadReceiver);
            downloadReceiver = null;
        }
        // Save text fields in a single batch
        SharedPreferences.Editor editor = prefs.edit();
        if (promptField != null) {
            editor.putString(SettingsDialog.KEY_WHISPER_INITIAL_PROMPT,
                    promptField.getText().toString());
        }
        editor.apply();
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
        int headerTextColor = InterfaceTheme.isLightColor(color) ? 0xFF1A1A1A : Color.WHITE;
        header.setTextColor(headerTextColor);
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

        // Engine selector
        body.addView(makeLabel("Engine"));
        String currentEngine = prefs.getString(SettingsDialog.KEY_TRANSCRIPTION_ENGINE,
                SettingsDialog.ENGINE_DEFAULT);
        Spinner engineSpinner = makeSpinner(SettingsDialog.ENGINE_LABELS);
        int engineIndex = findIndex(SettingsDialog.ENGINE_VALUES, currentEngine);
        engineSpinner.setSelection(engineIndex);
        body.addView(engineSpinner);

        // --- Whisper-specific controls ---
        LinearLayout whisperControls = new LinearLayout(this);
        whisperControls.setOrientation(LinearLayout.VERTICAL);

        String currentModel = prefs.getString(SettingsDialog.KEY_MODEL_NAME,
                SettingsDialog.DEFAULT_MODEL);
        TextView modelLabel = new TextView(this);
        modelLabel.setText("Current: " + currentModel);
        modelLabel.setTextColor(textColor);
        modelLabel.setTextSize(14);
        modelLabel.setPadding(0, 0, 0, dp(12));
        whisperControls.addView(modelLabel);

        Button browseBtn = makeActionButton("Browse...");
        browseBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, SettingsDialog.REQUEST_MODEL_FILE);
        });
        whisperControls.addView(browseBtn);

        String activeModel = prefs.getString(SettingsDialog.KEY_MODEL_NAME, SettingsDialog.DEFAULT_MODEL);
        whisperCatalogView = new WhisperCatalogView(this, new WhisperCatalogView.Listener() {
            @Override public void onDownload(com.voidterm.voice.WhisperModelCatalog.WhisperModel m) {
                if (ModelDownloadService.isRunning()) return; // one download at a time
                startForegroundService(new Intent(SettingsActivity.this, ModelDownloadService.class)
                        .setAction(ModelDownloadService.ACTION_START_DOWNLOAD)
                        .putExtra(DownloadJobs.EXTRA_JOB_TYPE, DownloadJobs.JOB_WHISPER)
                        .putExtra(ModelDownloadService.EXTRA_MODEL_ID, m.id));
                whisperCatalogView.onProgress(m.fileName, "Starting…");
            }
            @Override public void onActivate(com.voidterm.voice.WhisperModelCatalog.WhisperModel m) {
                activateWhisperModel(m.fileName);
            }
            @Override public void onDelete(com.voidterm.voice.WhisperModelCatalog.WhisperModel m) {
                confirmDeleteWhisperModel(m);
            }
        }, activeModel, textColor, mutedColor);
        whisperControls.addView(whisperCatalogView);

        boolean isWhisper = SettingsDialog.ENGINE_WHISPER.equals(currentEngine);
        whisperControls.setVisibility(isWhisper ? View.VISIBLE : View.GONE);
        body.addView(whisperControls);

        // --- Parakeet quantization selector (int8 / fp32) ---
        LinearLayout parakeetControls = new LinearLayout(this);
        parakeetControls.setOrientation(LinearLayout.VERTICAL);

        parakeetControls.addView(makeLabel("Quantization (modèle 0.6b multilingue v3)"));

        String activeQuant = prefs.getString(SettingsDialog.KEY_PARAKEET_QUANTIZATION,
                SettingsDialog.PARAKEET_QUANT_DEFAULT);
        parakeetQuantizationView = new ParakeetQuantizationView(this,
                new ParakeetQuantizationView.Listener() {
            @Override public void onDownload(ParakeetQuantization q) {
                if (ModelDownloadService.isRunning()) return; // one download at a time
                startForegroundService(new Intent(SettingsActivity.this, ModelDownloadService.class)
                        .setAction(ModelDownloadService.ACTION_START_DOWNLOAD)
                        .putExtra(DownloadJobs.EXTRA_JOB_TYPE, ParakeetDownloadJob.JOB_TYPE)
                        .putExtra(ModelDownloadService.EXTRA_MODEL_ID, q.id));
                parakeetQuantizationView.onProgress(q.id, "Starting…");
            }
            @Override public void onActivate(ParakeetQuantization q) {
                activateParakeetQuant(q.id);
            }
            @Override public void onDelete(ParakeetQuantization q) {
                confirmDeleteParakeetQuant(q);
            }
        }, activeQuant, textColor, mutedColor);
        parakeetControls.addView(parakeetQuantizationView);

        TextView fp32Warning = new TextView(this);
        fp32Warning.setText("⚠ fp32 : très lourd, peut ramer / saturer la mémoire sur Quest 2 (6 Go).");
        fp32Warning.setTextSize(12);
        fp32Warning.setTextColor(0xFFFF9800);
        fp32Warning.setPadding(0, dp(8), 0, dp(8));
        parakeetControls.addView(fp32Warning);

        parakeetControls.setVisibility(isWhisper ? View.GONE : View.VISIBLE);
        body.addView(parakeetControls);

        // Engine switch listener
        engineSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                String selected = SettingsDialog.ENGINE_VALUES[pos];
                prefs.edit()
                        .putString(SettingsDialog.KEY_TRANSCRIPTION_ENGINE, selected)
                        .putBoolean(SettingsDialog.KEY_MODEL_RELOAD_REQUESTED, true)
                        .apply();
                boolean whisper = SettingsDialog.ENGINE_WHISPER.equals(selected);
                whisperControls.setVisibility(whisper ? View.VISIBLE : View.GONE);
                parakeetControls.setVisibility(whisper ? View.GONE : View.VISIBLE);
                updateTranscriptionVisibility(whisper);
            }
        });

        return body;
    }

    /** Activate a downloaded quantization: write the pref + request a full engine reload. */
    private void activateParakeetQuant(String quantId) {
        prefs.edit()
                .putString(SettingsDialog.KEY_PARAKEET_QUANTIZATION, quantId)
                .putBoolean(SettingsDialog.KEY_MODEL_RELOAD_REQUESTED, true)
                .apply();
        if (parakeetQuantizationView != null) parakeetQuantizationView.setActive(quantId);
    }

    private void confirmDeleteParakeetQuant(ParakeetQuantization q) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Parakeet " + q.displayName + "?")
                .setMessage("Removes the " + q.displayName + " files (" + q.sizeMb + " MB).")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    String active = prefs.getString(SettingsDialog.KEY_PARAKEET_QUANTIZATION,
                            SettingsDialog.PARAKEET_QUANT_DEFAULT);
                    ParakeetModelManager.deleteModels(this, q);
                    if (q.id.equals(active)) {
                        // Active deleted: fall back to the other downloaded quantization, else int8.
                        String next = SettingsDialog.PARAKEET_QUANT_DEFAULT;
                        for (ParakeetQuantization other
                                : ParakeetQuantization.ALL) {
                            if (!other.id.equals(q.id)
                                    && ParakeetModelManager.isModelComplete(this, other)) {
                                next = other.id;
                                break;
                            }
                        }
                        prefs.edit()
                                .putString(SettingsDialog.KEY_PARAKEET_QUANTIZATION, next)
                                .putBoolean(SettingsDialog.KEY_MODEL_RELOAD_REQUESTED, true)
                                .apply();
                        if (parakeetQuantizationView != null) parakeetQuantizationView.setActive(next);
                    } else if (parakeetQuantizationView != null) {
                        parakeetQuantizationView.setActive(active);
                    }
                })
                .show();
    }

    /** Handle a progress/complete/error broadcast from {@link ModelDownloadService}. */
    private void onDownloadBroadcast(Intent intent) {
        String action = intent.getAction();
        String text = intent.getStringExtra(ModelDownloadService.EXTRA_TEXT);
        String modelId = intent.getStringExtra(ModelDownloadService.EXTRA_MODEL_ID);

        boolean whisper = modelId != null
                && com.voidterm.voice.WhisperModelCatalog.byFileName(modelId) != null;

        if (whisper) {
            if (whisperCatalogView == null) return;
            if (ModelDownloadService.BROADCAST_PROGRESS.equals(action)) {
                whisperCatalogView.onProgress(modelId, text != null ? text : "");
            } else if (ModelDownloadService.BROADCAST_COMPLETE.equals(action)) {
                whisperCatalogView.onDownloadEnded(modelId); // model becomes active
            } else if (ModelDownloadService.BROADCAST_ERROR.equals(action)) {
                whisperCatalogView.onDownloadEnded(null);
            }
            return;
        }

        // Parakeet path (quantization id "int8"/"fp32")
        if (parakeetQuantizationView == null) return;
        if (ModelDownloadService.BROADCAST_PROGRESS.equals(action)) {
            parakeetQuantizationView.onProgress(modelId, text != null ? text : "");
        } else if (ModelDownloadService.BROADCAST_COMPLETE.equals(action)) {
            parakeetQuantizationView.onDownloadEnded(modelId); // quantization becomes active
        } else if (ModelDownloadService.BROADCAST_ERROR.equals(action)) {
            parakeetQuantizationView.onDownloadEnded(null);
        }
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
                .putString(SettingsDialog.KEY_TRANSCRIPTION_ENGINE, SettingsDialog.ENGINE_WHISPER)
                .putBoolean(SettingsDialog.KEY_MODEL_RELOAD_REQUESTED, true)
                .apply();

        if (whisperCatalogView != null) whisperCatalogView.setActive(filename);
    }

    /** Activate a downloaded whisper model: set it active AND switch the engine to whisper. */
    private void activateWhisperModel(String fileName) {
        prefs.edit()
                .putString(SettingsDialog.KEY_MODEL_NAME, fileName)
                .putString(SettingsDialog.KEY_TRANSCRIPTION_ENGINE, SettingsDialog.ENGINE_WHISPER)
                .putBoolean(SettingsDialog.KEY_MODEL_RELOAD_REQUESTED, true)
                .apply();
        if (whisperCatalogView != null) whisperCatalogView.setActive(fileName);
    }

    private void confirmDeleteWhisperModel(com.voidterm.voice.WhisperModelCatalog.WhisperModel m) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + m.displayName + "?")
                .setMessage("Remove " + m.fileName + " (" + m.sizeMb + " MB)?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    String active = prefs.getString(SettingsDialog.KEY_MODEL_NAME,
                            SettingsDialog.DEFAULT_MODEL);
                    com.voidterm.voice.WhisperModelManager.delete(this, m.fileName);
                    if (m.fileName.equals(active)) {
                        String next = com.voidterm.voice.WhisperModelManager
                                .nextActiveAfterDelete(this, m.fileName);
                        prefs.edit().putString(SettingsDialog.KEY_MODEL_NAME,
                                next != null ? next : SettingsDialog.DEFAULT_MODEL).apply();
                        if (whisperCatalogView != null) {
                            whisperCatalogView.setActive(next != null ? next : SettingsDialog.DEFAULT_MODEL);
                        }
                    } else if (whisperCatalogView != null) {
                        whisperCatalogView.setActive(active);
                    }
                })
                .show();
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
        boolean isWhisper = SettingsDialog.ENGINE_WHISPER.equals(
                prefs.getString(SettingsDialog.KEY_TRANSCRIPTION_ENGINE, SettingsDialog.ENGINE_DEFAULT));

        // --- Whisper-only controls container ---
        whisperTranscriptionControls = new LinearLayout(this);
        whisperTranscriptionControls.setOrientation(LinearLayout.VERTICAL);
        whisperTranscriptionControls.setVisibility(isWhisper ? View.VISIBLE : View.GONE);

        // Auto-tune tier info
        String tierName = prefs.getString(DeviceProfiler.KEY_AUTOTUNE_TIER, null);
        if (tierName != null) {
            long benchmarkMs = prefs.getLong(DeviceProfiler.KEY_AUTOTUNE_BENCHMARK_MS, 0);
            TextView tierInfo = new TextView(this);
            tierInfo.setText("Performance: " + tierName.charAt(0)
                    + tierName.substring(1).toLowerCase() + " (" + benchmarkMs + "ms)");
            tierInfo.setTextColor(mutedColor);
            tierInfo.setTextSize(13);
            tierInfo.setPadding(0, 0, 0, dp(8));
            whisperTranscriptionControls.addView(tierInfo);
        }

        // Reset to Auto button
        Button resetAutoBtn = makeActionButton("Reset to Auto");
        resetAutoBtn.setOnClickListener(v -> {
            DeviceProfiler.resetToAuto(prefs);
            prefs.edit().putBoolean(SettingsDialog.KEY_MODEL_RELOAD_REQUESTED, true).apply();
            finish();
        });
        whisperTranscriptionControls.addView(resetAutoBtn);

        body.addView(whisperTranscriptionControls);

        // --- Shared controls (both engines) ---

        // Language spinner
        body.addView(makeLabel("Language"));
        String currentLang = prefs.getString(SettingsDialog.KEY_WHISPER_LANGUAGE, "en");
        Spinner langSpinner = makeSpinner(SettingsDialog.LANGUAGE_LABELS);
        int langIndex = findIndex(SettingsDialog.LANGUAGE_CODES, currentLang);
        langSpinner.setSelection(langIndex);
        body.addView(langSpinner);

        // Translate checkbox (whisper-only but uses shared language key)
        CheckBox translateToggle = makeCheckBox("Translate to English",
                prefs.getBoolean(SettingsDialog.KEY_WHISPER_TRANSLATE, false));
        translateToggle.setEnabled(!"en".equals(currentLang));
        translateToggle.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(SettingsDialog.KEY_WHISPER_TRANSLATE, checked).apply());
        translateToggle.setVisibility(isWhisper ? View.VISIBLE : View.GONE);
        body.addView(translateToggle);

        langSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putString(SettingsDialog.KEY_WHISPER_LANGUAGE,
                        SettingsDialog.LANGUAGE_CODES[pos]).apply();
                translateToggle.setEnabled(!"en".equals(SettingsDialog.LANGUAGE_CODES[pos]));
            }
        });

        // Initial prompt (whisper-only)
        TextView promptLabel = makeLabel("Initial prompt");
        promptLabel.setVisibility(isWhisper ? View.VISIBLE : View.GONE);
        body.addView(promptLabel);

        promptField = new EditText(this);
        promptField.setHint("Domain terms, names...");
        promptField.setTextColor(textColor);
        promptField.setHintTextColor(hintColor);
        promptField.setTextSize(14);
        promptField.setSingleLine(true);
        promptField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});
        promptField.setText(prefs.getString(SettingsDialog.KEY_WHISPER_INITIAL_PROMPT, ""));
        promptField.setVisibility(isWhisper ? View.VISIBLE : View.GONE);
        body.addView(promptField);

        // Direct-send toggle (both engines): bypass the review/validation overlay.
        // Whisper additionally displays text progressively; Parakeet delivers the
        // final text. Auto-submit (below) presses Enter so the command runs itself.
        boolean directSendOn = SettingsDialog.isDirectSendEnabled(prefs);
        CheckBox directSendToggle = makeCheckBox("Send directly (skip review)", directSendOn);
        body.addView(directSendToggle);

        TextView directSendWarning = new TextView(this);
        directSendWarning.setText("\u26A0 Sends text straight to the terminal without review. You cannot edit or cancel before it is inserted.");
        directSendWarning.setTextSize(12);
        directSendWarning.setTextColor(0xFFFF9800);
        directSendWarning.setPadding(0, 0, 0, dp(8));
        directSendWarning.setVisibility(directSendOn ? View.VISIBLE : View.GONE);
        body.addView(directSendWarning);

        // Auto-submit sub-toggle: only meaningful when direct-send is on.
        CheckBox autoSubmitToggle = makeCheckBox("Auto-submit (press Enter)",
                prefs.getBoolean(SettingsDialog.KEY_VOICE_AUTO_SUBMIT, false));
        autoSubmitToggle.setEnabled(directSendOn);
        autoSubmitToggle.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(SettingsDialog.KEY_VOICE_AUTO_SUBMIT, checked).apply());
        body.addView(autoSubmitToggle);

        directSendToggle.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(SettingsDialog.KEY_VOICE_DIRECT_SEND, checked).apply();
            directSendWarning.setVisibility(checked ? View.VISIBLE : View.GONE);
            autoSubmitToggle.setEnabled(checked);
        });

        // Audio preprocessing toggle (shared)
        CheckBox preprocessingToggle = makeCheckBox(
                "Voice preprocessing (emphasis + normalize)",
                prefs.getBoolean(SettingsDialog.KEY_AUDIO_PREPROCESSING, true));
        body.addView(preprocessingToggle);

        // Audio Tuning button (shared)
        Button tuningBtn = makeActionButton("Audio Tuning...");
        tuningBtn.setEnabled(preprocessingToggle.isChecked());
        tuningBtn.setOnClickListener(v -> new AudioDebugDialog(this).show());
        body.addView(tuningBtn);

        preprocessingToggle.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(SettingsDialog.KEY_AUDIO_PREPROCESSING, checked).apply();
            tuningBtn.setEnabled(checked);
        });

        // --- Advanced (whisper-only, collapsible) ---
        View advancedDivider = makeDivider();
        advancedDivider.setVisibility(isWhisper ? View.VISIBLE : View.GONE);
        body.addView(advancedDivider);

        LinearLayout advancedContainer = new LinearLayout(this);
        advancedContainer.setOrientation(LinearLayout.VERTICAL);
        advancedContainer.setVisibility(View.GONE);

        Button advancedBtn = makeActionButton("Advanced...");
        advancedBtn.setVisibility(isWhisper ? View.VISIBLE : View.GONE);
        advancedBtn.setOnClickListener(v -> {
            if (advancedContainer.getVisibility() == View.GONE) {
                advancedContainer.setVisibility(View.VISIBLE);
                advancedBtn.setText("Advanced \u25B2");
            } else {
                advancedContainer.setVisibility(View.GONE);
                advancedBtn.setText("Advanced...");
            }
        });
        body.addView(advancedBtn);

        // Temperature spinner
        advancedContainer.addView(makeLabel("Temperature"));
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
        advancedContainer.addView(tempSpinner);

        // Beam search toggle + beam size spinner
        CheckBox beamToggle = makeCheckBox("Use beam search",
                prefs.getBoolean(SettingsDialog.KEY_WHISPER_BEAM_SEARCH, false));
        advancedContainer.addView(beamToggle);

        LinearLayout beamSizeRow = new LinearLayout(this);
        beamSizeRow.setOrientation(LinearLayout.HORIZONTAL);
        beamSizeRow.setGravity(Gravity.CENTER_VERTICAL);
        beamSizeRow.setVisibility(
                prefs.getBoolean(SettingsDialog.KEY_WHISPER_BEAM_SEARCH, false)
                        ? View.VISIBLE : View.GONE);

        TextView beamSizeLabel = new TextView(this);
        beamSizeLabel.setText("Beam size: ");
        beamSizeLabel.setTextColor(textColor);
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
        advancedContainer.addView(beamSizeRow);

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
        advancedContainer.addView(suppressToggle);

        // Thread count override
        advancedContainer.addView(makeLabel("Threads"));
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
        advancedContainer.addView(threadSpinner);

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
        advancedContainer.addView(proportionalToggle);

        body.addView(advancedContainer);

        // --- Advanced (Parakeet-only, collapsible) ---
        addParakeetAdvanced(body, isWhisper);

        return body;
    }

    /**
     * Parakeet (ONNX) tunables behind a collapsible "Advanced" group, shown only when
     * the Parakeet engine is selected. Thread count changes the ONNX session, so it
     * requests a model reload; the chunking params are read fresh per transcription.
     */
    private void addParakeetAdvanced(LinearLayout body, boolean isWhisper) {
        View divider = makeDivider();
        divider.setVisibility(isWhisper ? View.GONE : View.VISIBLE);
        body.addView(divider);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setVisibility(View.GONE);

        Button advancedBtn = makeActionButton("Parakeet Advanced...");
        advancedBtn.setVisibility(isWhisper ? View.GONE : View.VISIBLE);
        advancedBtn.setOnClickListener(v -> {
            if (container.getVisibility() == View.GONE) {
                container.setVisibility(View.VISIBLE);
                advancedBtn.setText("Parakeet Advanced ▲");
            } else {
                container.setVisibility(View.GONE);
                advancedBtn.setText("Parakeet Advanced...");
            }
        });
        body.addView(advancedBtn);

        // Thread count override (0 = Auto). Changing threads rebuilds the ONNX session.
        container.addView(makeLabel("Threads"));
        String[] threadOptions = {"Auto", "1", "2", "3", "4", "5", "6", "7", "8"};
        Spinner threadSpinner = makeSpinner(threadOptions);
        int currentThreads = prefs.getInt(SettingsDialog.KEY_PARAKEET_THREAD_OVERRIDE, 0);
        threadSpinner.setSelection(Math.max(0, Math.min(currentThreads, threadOptions.length - 1)));
        threadSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit()
                        .putInt(SettingsDialog.KEY_PARAKEET_THREAD_OVERRIDE, pos)
                        .putBoolean(SettingsDialog.KEY_MODEL_RELOAD_REQUESTED, true)
                        .apply();
            }
        });
        container.addView(threadSpinner);

        // Max audio window per inference pass (seconds). Audio longer than this is
        // chunked at silence boundaries. Clamped to a tested-safe ceiling.
        container.addView(makeLabel("Max audio per pass"));
        String[] windowLabels = {"15 s", "20 s", "25 s", "30 s"};
        int[] windowValues = {15, 20, 25, 30};
        Spinner windowSpinner = makeSpinner(windowLabels);
        int currentWindow = prefs.getInt(SettingsDialog.KEY_PARAKEET_MAX_WINDOW_SEC, 30);
        windowSpinner.setSelection(nearestIndex(windowValues, currentWindow));
        windowSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putInt(SettingsDialog.KEY_PARAKEET_MAX_WINDOW_SEC, windowValues[pos]).apply();
            }
        });
        container.addView(windowSpinner);

        // Chunk overlap (seconds) — only used on continuous-speech fallback splits.
        container.addView(makeLabel("Chunk overlap"));
        String[] overlapLabels = {"0.5 s", "1.0 s", "1.5 s", "2.0 s"};
        float[] overlapValues = {0.5f, 1.0f, 1.5f, 2.0f};
        Spinner overlapSpinner = makeSpinner(overlapLabels);
        float currentOverlap = prefs.getFloat(SettingsDialog.KEY_PARAKEET_OVERLAP_SEC, 1.0f);
        overlapSpinner.setSelection(nearestIndex(overlapValues, currentOverlap));
        overlapSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putFloat(SettingsDialog.KEY_PARAKEET_OVERLAP_SEC, overlapValues[pos]).apply();
            }
        });
        container.addView(overlapSpinner);

        // Silence threshold (RMS) for choosing chunk cut points.
        container.addView(makeLabel("Silence sensitivity"));
        String[] silenceLabels = {"0.003 (sensitive)", "0.005", "0.008", "0.012 (strict)"};
        float[] silenceValues = {0.003f, 0.005f, 0.008f, 0.012f};
        Spinner silenceSpinner = makeSpinner(silenceLabels);
        float currentSilence = prefs.getFloat(SettingsDialog.KEY_PARAKEET_SILENCE_THRESHOLD, 0.005f);
        silenceSpinner.setSelection(nearestIndex(silenceValues, currentSilence));
        silenceSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putFloat(SettingsDialog.KEY_PARAKEET_SILENCE_THRESHOLD, silenceValues[pos]).apply();
            }
        });
        container.addView(silenceSpinner);

        // Max tokens per encoder frame (forward-progress guard).
        container.addView(makeLabel("Max tokens per step"));
        String[] tokenLabels = {"5", "10", "15", "20"};
        int[] tokenValues = {5, 10, 15, 20};
        Spinner tokenSpinner = makeSpinner(tokenLabels);
        int currentTokens = prefs.getInt(SettingsDialog.KEY_PARAKEET_MAX_TOKENS_STEP, 10);
        tokenSpinner.setSelection(nearestIndex(tokenValues, currentTokens));
        tokenSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putInt(SettingsDialog.KEY_PARAKEET_MAX_TOKENS_STEP, tokenValues[pos]).apply();
            }
        });
        container.addView(tokenSpinner);

        body.addView(container);
    }

    /** Index of the array entry closest to {@code target} (for value-keyed spinners). */
    private static int nearestIndex(int[] values, int target) {
        int best = 0, bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            int diff = Math.abs(values[i] - target);
            if (diff < bestDiff) { bestDiff = diff; best = i; }
        }
        return best;
    }

    private static int nearestIndex(float[] values, float target) {
        int best = 0;
        float bestDiff = Float.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            float diff = Math.abs(values[i] - target);
            if (diff < bestDiff) { bestDiff = diff; best = i; }
        }
        return best;
    }

    /** Show/hide whisper-specific controls in the Transcription section. */
    private void updateTranscriptionVisibility(boolean isWhisper) {
        if (whisperTranscriptionControls != null) {
            whisperTranscriptionControls.setVisibility(isWhisper ? View.VISIBLE : View.GONE);
        }
        // Rebuild the transcription section to update visibility of individual controls
        // This is triggered on engine change — simpler than tracking all individual views
        if (sectionBodies[SECTION_TRANSCRIPTION] != null) {
            int index = root.indexOfChild(sectionBodies[SECTION_TRANSCRIPTION]);
            if (index >= 0) {
                root.removeViewAt(index);
                sectionBodies[SECTION_TRANSCRIPTION] = buildTranscriptionSection();
                root.addView(sectionBodies[SECTION_TRANSCRIPTION], index);
                sectionBodies[SECTION_TRANSCRIPTION].setVisibility(
                        expandedSection == SECTION_TRANSCRIPTION ? View.VISIBLE : View.GONE);
            }
        }
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
                    recreate();
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

        body.addView(makeSubheading("Back Key"));
        addGestureRow(body, "Single tap",
                SettingsDialog.KEY_BACK_BEHAVIOR, SettingsDialog.KEY_BACK_MACRO,
                BACK_SINGLE_LABELS, BACK_SINGLE_VALUES, SettingsDialog.BACK_ESCAPE);

        LinearLayout adv = addAdvancedGroup(body);
        addGestureRow(adv, "Double tap",
                SettingsDialog.KEY_BACK_DOUBLE, SettingsDialog.KEY_BACK_DOUBLE_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);
        addGestureRow(adv, "Triple tap",
                SettingsDialog.KEY_BACK_TRIPLE, SettingsDialog.KEY_BACK_TRIPLE_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);
        addGestureRow(adv, "Long press",
                SettingsDialog.KEY_BACK_LONG, SettingsDialog.KEY_BACK_LONG_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);

        return body;
    }

    // --- Section: Volume Keys ---

    private LinearLayout buildVolumeKeysSection() {
        LinearLayout body = makeSectionBody();

        // Gesture sensitivity preset (applies to all keys)
        body.addView(makeLabel("Gesture sensitivity"));
        Spinner presetSpinner = makeSpinner(SettingsDialog.GESTURE_PRESET_LABELS);
        String currentPreset = prefs.getString(
                SettingsDialog.KEY_GESTURE_TIMING_PRESET, SettingsDialog.PRESET_NORMAL);
        presetSpinner.setSelection(findIndex(SettingsDialog.GESTURE_PRESET_VALUES, currentPreset));
        presetSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putString(SettingsDialog.KEY_GESTURE_TIMING_PRESET,
                        SettingsDialog.GESTURE_PRESET_VALUES[pos]).apply();
            }
        });
        body.addView(presetSpinner);
        body.addView(makeDivider());

        // Volume Up
        body.addView(makeSubheading("Volume Up"));
        addGestureRow(body, "Single tap",
                SettingsDialog.KEY_VOLUME_UP_BEHAVIOR, SettingsDialog.KEY_VOLUME_UP_MACRO,
                VOLUME_SINGLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);
        LinearLayout upAdv = addAdvancedGroup(body);
        addGestureRow(upAdv, "Double tap",
                SettingsDialog.KEY_VOLUP_DOUBLE, SettingsDialog.KEY_VOLUP_DOUBLE_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);
        addGestureRow(upAdv, "Triple tap",
                SettingsDialog.KEY_VOLUP_TRIPLE, SettingsDialog.KEY_VOLUP_TRIPLE_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);
        addGestureRow(upAdv, "Long press",
                SettingsDialog.KEY_VOLUP_LONG, SettingsDialog.KEY_VOLUP_LONG_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);

        body.addView(makeDivider());

        // Volume Down
        body.addView(makeSubheading("Volume Down"));
        addGestureRow(body, "Single tap",
                SettingsDialog.KEY_VOLUME_DOWN_BEHAVIOR, SettingsDialog.KEY_VOLUME_DOWN_MACRO,
                VOLUME_SINGLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);
        LinearLayout downAdv = addAdvancedGroup(body);
        addGestureRow(downAdv, "Double tap",
                SettingsDialog.KEY_VOLDOWN_DOUBLE, SettingsDialog.KEY_VOLDOWN_DOUBLE_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);
        addGestureRow(downAdv, "Triple tap",
                SettingsDialog.KEY_VOLDOWN_TRIPLE, SettingsDialog.KEY_VOLDOWN_TRIPLE_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);
        addGestureRow(downAdv, "Long press",
                SettingsDialog.KEY_VOLDOWN_LONG, SettingsDialog.KEY_VOLDOWN_LONG_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);

        body.addView(makeDivider());

        // Combo (Vol+ & Vol-)
        body.addView(makeSubheading("Combo (Vol+ & Vol-)"));
        addGestureRow(body, "Single",
                SettingsDialog.KEY_COMBO_SINGLE, SettingsDialog.KEY_COMBO_SINGLE_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);
        addGestureRow(body, "Double",
                SettingsDialog.KEY_COMBO_DOUBLE, SettingsDialog.KEY_COMBO_DOUBLE_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);
        addGestureRow(body, "Triple",
                SettingsDialog.KEY_COMBO_TRIPLE, SettingsDialog.KEY_COMBO_TRIPLE_MACRO,
                NONEABLE_LABELS, GESTURE_VALUES_WITH_DEFAULT, SettingsDialog.VOLUME_DEFAULT);

        return body;
    }

    /**
     * Adds a labelled behavior spinner + conditional macro field bound to the
     * given preference keys. Shared by every key-gesture slot. The macro field
     * appears only when "Macro" is selected. {@code values}/{@code labels} pick
     * the option set; {@code defaultBehavior} is the pref read fallback (and the
     * preselected value for a fresh install).
     */
    private void addGestureRow(LinearLayout parent, String label,
                               String behaviorKey, String macroKey,
                               String[] labels, String[] values, String defaultBehavior) {
        final int macroIndex = findIndex(values, SettingsDialog.BACK_MACRO);

        parent.addView(makeLabel(label));
        Spinner spinner = makeSpinner(labels);
        String current = prefs.getString(behaviorKey, defaultBehavior);
        spinner.setSelection(findIndex(values, current));
        parent.addView(spinner);

        final EditText macro = new EditText(this);
        macro.setHint("Macro command (e.g. {ctrl+c})");
        macro.setTextColor(textColor);
        macro.setHintTextColor(hintColor);
        macro.setTextSize(14);
        macro.setSingleLine(true);
        macro.setText(prefs.getString(macroKey, ""));
        macro.setVisibility(
                SettingsDialog.BACK_MACRO.equals(current) ? View.VISIBLE : View.GONE);
        parent.addView(macro);

        macro.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(android.text.Editable e) {
                prefs.edit().putString(macroKey, e.toString()).apply();
            }
        });

        spinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putString(behaviorKey, values[pos]).apply();
                macro.setVisibility(pos == macroIndex ? View.VISIBLE : View.GONE);
            }
        });
    }

    /** Adds a collapsible "Advanced" sub-group; rows added to the returned container. */
    private LinearLayout addAdvancedGroup(LinearLayout parent) {
        final LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setVisibility(View.GONE);

        final Button toggle = makeActionButton("Advanced...");
        toggle.setOnClickListener(v -> {
            boolean show = container.getVisibility() != View.VISIBLE;
            container.setVisibility(show ? View.VISIBLE : View.GONE);
            toggle.setText(show ? "Advanced ▲" : "Advanced...");
        });
        parent.addView(toggle);
        parent.addView(container);
        return container;
    }

    private void computeDerivedColors() {
        boolean light = InterfaceTheme.isLightColor(theme.background);
        surfaceColor = theme.background;
        bodyColor = light
                ? InterfaceTheme.darkenColor(theme.background, 0.9f)
                : InterfaceTheme.lightenColor(theme.background, 1.3f);
        textColor = light ? 0xFF1A1A1A : Color.WHITE;
        mutedColor = light ? 0xFF555555 : 0xFFCCCCCC;
        hintColor = light ? 0x88666666 : 0xAA999999;
    }

    // --- Widget factories ---

    private LinearLayout makeSectionBody() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(12), dp(16), dp(16));
        body.setBackgroundColor(bodyColor);
        return body;
    }

    private TextView makeLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(mutedColor);
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
        cb.setTextColor(textColor);
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
                    ((TextView) view).setTextColor(textColor);
                }
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                if (view instanceof TextView) {
                    ((TextView) view).setTextColor(textColor);
                    view.setBackgroundColor(surfaceColor);
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
        int btnTextColor = InterfaceTheme.isLightColor(theme.primary) ? 0xFF1A1A1A : Color.WHITE;
        btn.setTextColor(btnTextColor);
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

    /** TextWatcher stub that only needs afterTextChanged. */
    private static abstract class SimpleTextWatcher implements android.text.TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
    }
}
