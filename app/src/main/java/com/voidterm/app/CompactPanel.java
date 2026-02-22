package com.voidterm.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * Compact control panel with 3 rows of 6 buttons (128dp total height).
 * Provides all GameBoy panel features in a denser layout.
 *
 * Row 1 (Modifiers + Tabs): ESC | CTL | SHF | TAB | S-TAB | S-ENT
 * Row 2 (Navigation + Action): Left | Up | Down | Right | Enter | STT
 * Row 3 (Macros + Menu): M1 | M2 | M3 | M4 | PG | Menu
 *
 * Uses the same ControlPanelListener interface as GameBoyControlPanel.
 */
public class CompactPanel extends FrameLayout {

    private final InterfaceTheme theme;

    private GameBoyControlPanel.ControlPanelListener listener;
    private boolean ctrlActive;
    private boolean shiftActive;
    private Button ctrlButton;
    private Button shiftButton;

    private String[][] macros;
    private final Button[] macroButtons = new Button[4];
    private int currentMacroPage = 0;
    private Button macroPageButton;

    private Runnable activeRepeatRunnable;
    private View activeRepeatView;

    private volatile boolean hapticEnabled;
    private final SharedPreferences.OnSharedPreferenceChangeListener hapticListener =
            (prefs, key) -> {
                if (SettingsDialog.KEY_HAPTIC_FEEDBACK.equals(key)) {
                    hapticEnabled = prefs.getBoolean(SettingsDialog.KEY_HAPTIC_FEEDBACK, true);
                }
            };

    public CompactPanel(Context context) {
        super(context);
        theme = InterfaceTheme.current(context);
        setBackgroundColor(theme.background);
        macros = MacroStore.load(context);

        SharedPreferences prefs = context.getSharedPreferences(
                SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE);
        hapticEnabled = prefs.getBoolean(SettingsDialog.KEY_HAPTIC_FEEDBACK, true);
        prefs.registerOnSharedPreferenceChangeListener(hapticListener);

        buildLayout(context);
    }

    public void setControlPanelListener(GameBoyControlPanel.ControlPanelListener listener) {
        this.listener = listener;
    }

    private void buildLayout(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(4);
        root.setPadding(pad, pad, pad, pad);

        root.addView(buildRow1(context), rowParams());
        root.addView(buildRow2(context), rowParamsWithTopMargin());
        root.addView(buildRow3(context), rowParamsWithTopMargin());

        addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // --- Row 1: ESC | CTL | SHF | TAB | S-TAB | S-ENT ---

    private LinearLayout buildRow1(Context context) {
        LinearLayout row = makeRow(context);

        // ESC
        addButton(row, context, "ESC", theme.modifier, v -> {
            if (listener != null) listener.onSendToTerminal("\033");
        });

        // CTL (sticky toggle)
        ctrlButton = makeButton(context, "CTL", theme.modifier);
        ctrlButton.setOnClickListener(v -> {
            if (hapticEnabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            ctrlActive = !ctrlActive;
            updateModifierButtonColor(ctrlButton, ctrlActive);
        });
        row.addView(ctrlButton, buttonParams());

        // SHF (sticky toggle)
        shiftButton = makeButton(context, "SHF", theme.modifier);
        shiftButton.setOnClickListener(v -> {
            if (hapticEnabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            shiftActive = !shiftActive;
            updateModifierButtonColor(shiftButton, shiftActive);
        });
        row.addView(shiftButton, buttonParams());

        // TAB (respects shift for backtab)
        addButton(row, context, "TAB", theme.primary, v -> {
            if (listener != null) {
                if (shiftActive) {
                    listener.onSendToTerminal("\033[Z");
                    resetShift();
                } else {
                    listener.onSendToTerminal("\t");
                }
            }
        });

        // S-TAB (backtab)
        addButton(row, context, "S-TAB", theme.modifier, v -> {
            if (listener != null) listener.onSendToTerminal("\033[Z");
        });

        // S-ENT (newline without submit)
        addButton(row, context, "S-\u21B5", theme.modifier, v -> {
            if (listener != null) listener.onSendToTerminal("\n");
        });

        return row;
    }

    // --- Row 2: Left | Up | Down | Right | Enter | STT ---

    private LinearLayout buildRow2(Context context) {
        LinearLayout row = makeRow(context);

        // Left arrow (with repeat)
        Button left = makeButton(context, "\u25C0", theme.dpad);
        setupArrowRepeat(left, "\033[D");
        row.addView(left, buttonParams());

        // Up arrow (with repeat)
        Button up = makeButton(context, "\u25B2", theme.dpad);
        setupArrowRepeat(up, "\033[A");
        row.addView(up, buttonParams());

        // Down arrow (with repeat)
        Button down = makeButton(context, "\u25BC", theme.dpad);
        setupArrowRepeat(down, "\033[B");
        row.addView(down, buttonParams());

        // Right arrow (with repeat)
        Button right = makeButton(context, "\u25B6", theme.dpad);
        setupArrowRepeat(right, "\033[C");
        row.addView(right, buttonParams());

        // Enter
        addButton(row, context, "\u21B5", theme.primary, v -> {
            if (listener != null) listener.onSendToTerminal("\r");
        });

        // STT (voice)
        addButton(row, context, "\uD83C\uDFA4", theme.dpad, v -> {
            if (listener != null) listener.onVoiceToggle();
        });

        return row;
    }

    // --- Row 3: M1 | M2 | M3 | M4 | PG | Menu ---

    private LinearLayout buildRow3(Context context) {
        LinearLayout row = makeRow(context);

        // 4 macro buttons
        for (int i = 0; i < 4; i++) {
            int actualIndex = currentMacroPage * MacroStore.PAGE_SIZE + i;
            Button btn = makeButton(context, macros[actualIndex][0], theme.macro);
            final int index = i;
            btn.setOnClickListener(v -> {
                if (hapticEnabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (listener != null) {
                    int ai = currentMacroPage * MacroStore.PAGE_SIZE + index;
                    MacroExecutor.execute(macros[ai][1],
                            listener::onSendToTerminal, v.getHandler());
                }
            });
            btn.setOnLongClickListener(v -> {
                if (hapticEnabled) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                int ai = currentMacroPage * MacroStore.PAGE_SIZE + index;
                MacroEditDialog.show(context, macros[ai][0], macros[ai][1],
                        (label, cmd) -> {
                            int ai2 = currentMacroPage * MacroStore.PAGE_SIZE + index;
                            macros[ai2][0] = label;
                            macros[ai2][1] = cmd;
                            macroButtons[index].setText(label);
                            MacroStore.save(context, macros);
                        });
                return true;
            });
            macroButtons[i] = btn;
            row.addView(btn, buttonParams());
        }

        // Page cycle button
        macroPageButton = makeButton(context,
                (currentMacroPage + 1) + "/" + MacroStore.PAGE_COUNT, theme.dpad);
        macroPageButton.setOnClickListener(v -> {
            if (hapticEnabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            currentMacroPage = (currentMacroPage + 1) % MacroStore.PAGE_COUNT;
            updateMacroPage();
        });
        row.addView(macroPageButton, buttonParams());

        // Burger menu (settings)
        addButton(row, context, "\u2630", theme.macro, v -> {
            if (listener != null) listener.onSettingsRequested();
        });

        return row;
    }

    // --- Macro page ---

    public int getCurrentMacroPage() {
        return currentMacroPage;
    }

    public void setCurrentMacroPage(int page) {
        if (page >= 0 && page < MacroStore.PAGE_COUNT) {
            currentMacroPage = page;
            updateMacroPage();
        }
    }

    private void updateMacroPage() {
        macros = MacroStore.load(getContext());
        for (int i = 0; i < MacroStore.PAGE_SIZE; i++) {
            macroButtons[i].setText(macros[currentMacroPage * MacroStore.PAGE_SIZE + i][0]);
        }
        macroPageButton.setText((currentMacroPage + 1) + "/" + MacroStore.PAGE_COUNT);
    }

    // --- Arrow repeat ---

    private void setupArrowRepeat(Button btn, String escSeq) {
        btn.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (hapticEnabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (listener != null) listener.onSendToTerminal(escSeq);
                    v.setPressed(true);
                    cancelRepeat();
                    Runnable repeat = new Runnable() {
                        @Override
                        public void run() {
                            if (v.isPressed()) {
                                if (listener != null) listener.onSendToTerminal(escSeq);
                                v.postDelayed(this, 100);
                            }
                        }
                    };
                    activeRepeatRunnable = repeat;
                    activeRepeatView = v;
                    v.postDelayed(repeat, 400);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    cancelRepeat();
                    return true;
            }
            return false;
        });
    }

    private void cancelRepeat() {
        if (activeRepeatRunnable != null && activeRepeatView != null) {
            activeRepeatView.removeCallbacks(activeRepeatRunnable);
        }
        activeRepeatRunnable = null;
        activeRepeatView = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelRepeat();
        getContext().getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(hapticListener);
    }

    // --- Modifier state API ---

    public boolean isCtrlActive() {
        return ctrlActive;
    }

    public void setCtrlActive(boolean active) {
        ctrlActive = active;
        updateModifierButtonColor(ctrlButton, ctrlActive);
    }

    public void resetCtrl() {
        ctrlActive = false;
        updateModifierButtonColor(ctrlButton, false);
    }

    public boolean isShiftActive() {
        return shiftActive;
    }

    public void setShiftActive(boolean active) {
        shiftActive = active;
        updateModifierButtonColor(shiftButton, shiftActive);
    }

    public void resetShift() {
        shiftActive = false;
        updateModifierButtonColor(shiftButton, false);
    }

    // --- Button helpers ---

    private LinearLayout makeRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams rowParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    }

    private LinearLayout.LayoutParams rowParamsWithTopMargin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(2);
        return lp;
    }

    private void addButton(LinearLayout row, Context context, String label, int color,
                           OnClickListener click) {
        Button btn = makeButton(context, label, color);
        btn.setOnClickListener(v -> {
            if (hapticEnabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            click.onClick(v);
        });
        row.addView(btn, buttonParams());
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private Button makeButton(Context ctx, String label, int bgColor) {
        Button btn = new Button(ctx);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setAllCaps(false);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(0, 0, 0, 0);
        btn.setMinWidth(0);
        btn.setMinHeight(0);
        btn.setMinimumWidth(0);
        btn.setMinimumHeight(0);
        btn.setBackground(makeButtonDrawable(bgColor));
        btn.setStateListAnimator(null);
        btn.setContentDescription(descriptionForLabel(label));
        return btn;
    }

    private static String descriptionForLabel(String label) {
        switch (label) {
            case "\u25B2": return "Up arrow";
            case "\u25BC": return "Down arrow";
            case "\u25C0": return "Left arrow";
            case "\u25B6": return "Right arrow";
            case "\u21B5": return "Enter";
            case "\uD83C\uDFA4": return "Voice input";
            case "\u2630": return "Menu";
            case "CTL": return "Control modifier";
            case "SHF": return "Shift modifier";
            case "ESC": return "Escape";
            case "TAB": return "Tab";
            case "S-TAB": return "Shift Tab";
            case "S-\u21B5": return "Shift Enter";
            default: return label;
        }
    }

    private StateListDrawable makeButtonDrawable(int bgColor) {
        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.RECTANGLE);
        normal.setCornerRadius(dp(6));
        normal.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        normal.setColors(new int[]{InterfaceTheme.lightenColor(bgColor, 1.4f),
                InterfaceTheme.darkenColor(bgColor, 0.85f)});
        normal.setStroke(dp(1), InterfaceTheme.darkenColor(bgColor, 0.55f));

        GradientDrawable pressed = new GradientDrawable();
        pressed.setShape(GradientDrawable.RECTANGLE);
        pressed.setCornerRadius(dp(6));
        pressed.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        pressed.setColors(new int[]{InterfaceTheme.darkenColor(bgColor, 0.6f),
                InterfaceTheme.darkenColor(bgColor, 0.8f)});
        pressed.setStroke(dp(1), InterfaceTheme.darkenColor(bgColor, 0.4f));

        StateListDrawable stateList = new StateListDrawable();
        stateList.addState(new int[]{android.R.attr.state_pressed}, pressed);
        stateList.addState(new int[]{}, normal);
        return stateList;
    }

    private void updateModifierButtonColor(Button btn, boolean active) {
        btn.setBackground(makeButtonDrawable(active ? theme.active : theme.modifier));
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
