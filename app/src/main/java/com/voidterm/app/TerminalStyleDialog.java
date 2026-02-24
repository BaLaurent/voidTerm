package com.voidterm.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.termux.terminal.TerminalColors;
import com.termux.terminal.TextStyle;
import com.termux.view.TerminalView;

/**
 * Dialog for customizing terminal appearance: font size, font family, and colors.
 * Fonts are loaded from assets/fonts/. Color schemes are built-in presets.
 */
public class TerminalStyleDialog {

    private static final String TAG = "TerminalStyleDialog";
    public static final String PREFS_NAME = "voidterm_style";
    public static final String KEY_FONT_SIZE = "font_size";
    private static final String KEY_FONT_INDEX = "font_index";
    private static final String KEY_COLOR_INDEX = "color_index";

    private static final int DEFAULT_FONT_SIZE = 20;

    // --- Font definitions (index 0 = system monospace, rest from assets/fonts/) ---

    private static final String[] FONT_NAMES = {
            "System Monospace",
            "JetBrains Mono",
            "Fira Code",
            "Hack",
            "Iosevka",
            "Source Code Pro",
            "Inconsolata",
            "Ubuntu Mono",
    };

    // null = system Typeface.MONOSPACE, otherwise asset path
    private static final String[] FONT_ASSETS = {
            null,
            "fonts/JetBrainsMono-Regular.ttf",
            "fonts/FiraCode-Regular.ttf",
            "fonts/Hack-Regular.ttf",
            "fonts/Iosevka-Regular.ttf",
            "fonts/SourceCodePro-Regular.ttf",
            "fonts/Inconsolata-Regular.ttf",
            "fonts/UbuntuMono-Regular.ttf",
    };

    // --- Color scheme definitions: {name, foreground, background} ---

    private static final String[] COLOR_NAMES = {
            // Dark backgrounds
            "Default",
            "Matrix",
            "Amber Retro",
            "Solarized Dark",
            "Dracula",
            "Nord",
            "Monokai",
            "Gruvbox Dark",
            "One Dark",
            "Tokyo Night",
            "Catppuccin Mocha",
            "Ayu Dark",
            "Synthwave",
            "Cyberpunk",
            // Light backgrounds
            "Solarized Light",
            "Gruvbox Light",
            "One Light",
            "Catppuccin Latte",
    };

    // {foreground, background}
    private static final int[][] COLOR_VALUES = {
            // Dark backgrounds
            {0xffffffff, 0xff000000},  // Default
            {0xff00ff00, 0xff000000},  // Matrix
            {0xffffb000, 0xff000000},  // Amber Retro
            {0xff839496, 0xff002b36},  // Solarized Dark
            {0xfff8f8f2, 0xff282a36},  // Dracula
            {0xffd8dee9, 0xff2e3440},  // Nord
            {0xfff8f8f2, 0xff272822},  // Monokai
            {0xffebdbb2, 0xff282828},  // Gruvbox Dark
            {0xffabb2bf, 0xff282c34},  // One Dark
            {0xffa9b1d6, 0xff1a1b26},  // Tokyo Night
            {0xffcdd6f4, 0xff1e1e2e},  // Catppuccin Mocha
            {0xffbfbdb6, 0xff0d1017},  // Ayu Dark
            {0xfff0e3ff, 0xff2b213a},  // Synthwave
            {0xff00ffcc, 0xff0a0a1a},  // Cyberpunk
            // Light backgrounds
            {0xff657b83, 0xfffdf6e3},  // Solarized Light
            {0xff3c3836, 0xfffbf1c7},  // Gruvbox Light
            {0xff383a42, 0xfffafafa},  // One Light
            {0xff4c4f69, 0xffeff1f5},  // Catppuccin Latte
    };

    private final TermuxActivity activity;
    private final TerminalView terminalView;

    public TerminalStyleDialog(TermuxActivity activity, TerminalView terminalView) {
        this.activity = activity;
        this.terminalView = terminalView;
    }

    public void show() {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int currentSize = prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE);
        int currentFontIndex = prefs.getInt(KEY_FONT_INDEX, 0);
        int currentColorIndex = prefs.getInt(KEY_COLOR_INDEX, 0);

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);

        // --- Font Size ---
        addSectionLabel(layout, "Font size: " + currentSize);
        TextView sizeLabel = (TextView) layout.getChildAt(layout.getChildCount() - 1);

        SeekBar sizeBar = new SeekBar(activity);
        sizeBar.setMax(50); // range 6..56
        sizeBar.setProgress(currentSize - 6);
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                sizeLabel.setText("Font size: " + (progress + 6));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int size = seekBar.getProgress() + 6;
                terminalView.setTextSize(size);
                prefs.edit().putInt(KEY_FONT_SIZE, size).apply();
            }
        });
        layout.addView(sizeBar);

        // --- Font Family ---
        addSectionLabel(layout, "Font");

        for (int i = 0; i < FONT_NAMES.length; i++) {
            Button btn = new Button(activity);
            String label = FONT_NAMES[i];
            if (i == currentFontIndex) label += "  \u2713";
            btn.setText(label);
            btn.setAllCaps(false);
            btn.setTextSize(14);

            // Preview font on the button itself
            Typeface preview = loadTypeface(activity, i);
            if (preview != null) btn.setTypeface(preview);

            final int idx = i;
            btn.setOnClickListener(v -> {
                Typeface tf = loadTypeface(activity, idx);
                if (tf != null) {
                    terminalView.setTypeface(tf);
                    prefs.edit().putInt(KEY_FONT_INDEX, idx).apply();
                }
                // Update checkmarks
                for (int j = 0; j < FONT_NAMES.length; j++) {
                    Button b = findFontButton(layout, j);
                    if (b != null) {
                        b.setText(j == idx ? FONT_NAMES[j] + "  \u2713" : FONT_NAMES[j]);
                    }
                }
            });

            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            btnParams.setMargins(0, 2, 0, 2);
            btn.setLayoutParams(btnParams);
            btn.setTag("font_" + i);
            layout.addView(btn);
        }

        // --- Color Schemes ---
        addSectionLabel(layout, "Color scheme");

        for (int i = 0; i < COLOR_NAMES.length; i++) {
            final int fg = COLOR_VALUES[i][0];
            final int bg = COLOR_VALUES[i][1];
            final int idx = i;

            Button btn = new Button(activity);
            String label = COLOR_NAMES[i];
            if (i == currentColorIndex) label += "  \u2713";
            btn.setText(label);
            btn.setTextColor(fg);
            btn.setBackgroundColor(bg);
            btn.setAllCaps(false);
            btn.setTextSize(13);
            btn.setPadding(24, 8, 24, 8);

            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            btnParams.setMargins(0, 3, 0, 3);
            btn.setLayoutParams(btnParams);
            btn.setTag("color_" + i);

            btn.setOnClickListener(v -> {
                applyColorScheme(fg, bg);
                prefs.edit().putInt(KEY_COLOR_INDEX, idx).apply();
                // Update checkmarks
                for (int j = 0; j < COLOR_NAMES.length; j++) {
                    Button b = findColorButton(layout, j);
                    if (b != null) {
                        b.setText(j == idx ? COLOR_NAMES[j] + "  \u2713" : COLOR_NAMES[j]);
                    }
                }
            });
            layout.addView(btn);
        }

        // Wrap in ScrollView since we have many options
        ScrollView scrollView = new ScrollView(activity);
        scrollView.addView(layout);

        new AlertDialog.Builder(activity)
                .setTitle("Terminal Style")
                .setView(scrollView)
                .setPositiveButton("Close", null)
                .show();
    }

    private void addSectionLabel(LinearLayout layout, String text) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextSize(16);
        label.setPadding(0, 24, 0, 8);
        layout.addView(label);
    }

    private Button findFontButton(LinearLayout layout, int index) {
        return layout.findViewWithTag("font_" + index);
    }

    private Button findColorButton(LinearLayout layout, int index) {
        return layout.findViewWithTag("color_" + index);
    }

    private void applyColorScheme(int fg, int bg) {
        if (terminalView.mEmulator == null) return;
        TerminalColors colors = terminalView.mEmulator.mColors;
        colors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = fg;
        colors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = bg;
        int brightness = TerminalColors.getPerceivedBrightnessOfColor(bg);
        colors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = brightness < 130 ? 0xffffffff : 0xff000000;
        terminalView.invalidate();
    }

    /**
     * Load a typeface by font index. Index 0 = system monospace, others from assets.
     */
    private static Typeface loadTypeface(Context context, int fontIndex) {
        if (fontIndex < 0 || fontIndex >= FONT_ASSETS.length) return Typeface.MONOSPACE;
        String asset = FONT_ASSETS[fontIndex];
        if (asset == null) return Typeface.MONOSPACE;
        try {
            return Typeface.createFromAsset(context.getAssets(), asset);
        } catch (Exception e) {
            Log.w(TAG, "Failed to load font: " + asset, e);
            return Typeface.MONOSPACE;
        }
    }

    /**
     * Apply saved style preferences to the terminal view.
     * Call this after the terminal session is attached.
     */
    public static void applySavedStyle(TermuxActivity activity, TerminalView terminalView) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        int fontSize = prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE);
        terminalView.setTextSize(fontSize);

        int fontIndex = prefs.getInt(KEY_FONT_INDEX, 0);
        Typeface tf = loadTypeface(activity, fontIndex);
        if (tf != null) {
            terminalView.setTypeface(tf);
        }

        int colorIndex = prefs.getInt(KEY_COLOR_INDEX, -1);
        if (colorIndex >= 0 && colorIndex < COLOR_VALUES.length && terminalView.mEmulator != null) {
            int fg = COLOR_VALUES[colorIndex][0];
            int bg = COLOR_VALUES[colorIndex][1];
            TerminalColors colors = terminalView.mEmulator.mColors;
            colors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = fg;
            colors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = bg;
            int brightness = TerminalColors.getPerceivedBrightnessOfColor(bg);
            colors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = brightness < 130 ? 0xffffffff : 0xff000000;
            terminalView.invalidate();
        }
    }
}
