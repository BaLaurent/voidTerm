package com.voidterm.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.voidterm.contracts.ControlPanel;
import com.voidterm.contracts.ControlPanelListener;

/**
 * Compact control panel with 4 rows of 6 buttons (170dp total height).
 * All 12 macros are visible at once — no paging needed.
 *
 * Row 1 (Modifiers + Tabs): ESC | CTL | TAB | S-TAB | S-ENT | Menu
 * Row 2 (Navigation + Action): Left | Up | Down | Right | Enter | STT
 * Row 3 (Macros 1-6): M1 | M2 | M3 | M4 | M5 | M6
 * Row 4 (Macros 7-12): M7 | M8 | M9 | M10 | M11 | M12
 *
 * No Shift button — dedicated S-TAB and S-ENT buttons cover all shift uses.
 * Uses the same ControlPanelListener interface as GameBoyControlPanel.
 */
public class CompactPanel extends FrameLayout implements ControlPanel {

    private final InterfaceTheme theme;

    private ControlPanelListener listener;
    private boolean ctrlActive;
    private Button ctrlButton;

    private String[][] macros;
    private final Button[] macroButtons = new Button[12];

    private final PanelUtils.RepeatState repeatState = new PanelUtils.RepeatState();

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

        hapticEnabled = context.getSharedPreferences(
                SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(SettingsDialog.KEY_HAPTIC_FEEDBACK, true);

        buildLayout(context);
    }

    public void setControlPanelListener(ControlPanelListener listener) {
        this.listener = listener;
    }

    private void buildLayout(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(4);
        root.setPadding(pad, pad, pad, pad);

        root.addView(buildRow1(context), rowParams());
        root.addView(buildRow2(context), rowParamsWithTopMargin());
        root.addView(buildMacroRow(context, 0), rowParamsWithTopMargin());
        root.addView(buildMacroRow(context, 6), rowParamsWithTopMargin());

        addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // --- Row 1: ESC | CTL | TAB | S-TAB | S-ENT | Menu ---

    private LinearLayout buildRow1(Context context) {
        LinearLayout row = makeRow(context);

        // ESC
        PanelUtils.addCompactButton(row, context, "ESC", theme.modifier, v -> {
            if (listener != null) listener.onSendToTerminal("\033");
        }, buttonParams(), () -> hapticEnabled);

        // CTL (sticky toggle)
        ctrlButton = PanelUtils.makeCompactButton(context, "CTL", theme.modifier);
        ctrlButton.setOnClickListener(v -> {
            if (hapticEnabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            ctrlActive = !ctrlActive;
            updateModifierButtonColor(ctrlButton, ctrlActive);
        });
        row.addView(ctrlButton, buttonParams());

        // TAB
        PanelUtils.addCompactButton(row, context, "TAB", theme.primary, v -> {
            if (listener != null) listener.onSendToTerminal("\t");
        }, buttonParams(), () -> hapticEnabled);

        // S-TAB (backtab)
        PanelUtils.addCompactButton(row, context, "S-TAB", theme.modifier, v -> {
            if (listener != null) listener.onSendToTerminal("\033[Z");
        }, buttonParams(), () -> hapticEnabled);

        // S-ENT (newline without submit)
        PanelUtils.addCompactButton(row, context, "S-\u21B5", theme.modifier, v -> {
            if (listener != null) listener.onSendToTerminal("\n");
        }, buttonParams(), () -> hapticEnabled);

        // Burger menu (settings)
        PanelUtils.addCompactButton(row, context, "\u2630", theme.macro, v -> {
            if (listener != null) listener.onSettingsRequested();
        }, buttonParams(), () -> hapticEnabled);

        return row;
    }

    // --- Row 2: Left | Up | Down | Right | Enter | STT ---

    private LinearLayout buildRow2(Context context) {
        LinearLayout row = makeRow(context);

        // Left arrow (with repeat)
        Button left = PanelUtils.makeCompactButton(context, "\u25C0", theme.dpad);
        PanelUtils.setupArrowRepeat(repeatState, left, "\033[D",
                text -> { if (listener != null) listener.onSendToTerminal(text); },
                () -> hapticEnabled);
        row.addView(left, buttonParams());

        // Up arrow (with repeat)
        Button up = PanelUtils.makeCompactButton(context, "\u25B2", theme.dpad);
        PanelUtils.setupArrowRepeat(repeatState, up, "\033[A",
                text -> { if (listener != null) listener.onSendToTerminal(text); },
                () -> hapticEnabled);
        row.addView(up, buttonParams());

        // Down arrow (with repeat)
        Button down = PanelUtils.makeCompactButton(context, "\u25BC", theme.dpad);
        PanelUtils.setupArrowRepeat(repeatState, down, "\033[B",
                text -> { if (listener != null) listener.onSendToTerminal(text); },
                () -> hapticEnabled);
        row.addView(down, buttonParams());

        // Right arrow (with repeat)
        Button right = PanelUtils.makeCompactButton(context, "\u25B6", theme.dpad);
        PanelUtils.setupArrowRepeat(repeatState, right, "\033[C",
                text -> { if (listener != null) listener.onSendToTerminal(text); },
                () -> hapticEnabled);
        row.addView(right, buttonParams());

        // Enter
        PanelUtils.addCompactButton(row, context, "\u21B5", theme.primary, v -> {
            if (listener != null) listener.onSendToTerminal("\r");
        }, buttonParams(), () -> hapticEnabled);

        // STT (voice)
        PanelUtils.addCompactButton(row, context, "\uD83C\uDFA4", theme.dpad, v -> {
            if (listener != null) listener.onVoiceToggle();
        }, buttonParams(), () -> hapticEnabled);

        return row;
    }

    // --- Macro rows: 6 macros per row ---

    private LinearLayout buildMacroRow(Context context, int startIndex) {
        LinearLayout row = makeRow(context);

        for (int i = 0; i < 6; i++) {
            final int idx = startIndex + i;
            Button btn = PanelUtils.makeCompactButton(context, macros[idx][0], theme.macro);
            btn.setOnClickListener(v -> {
                if (hapticEnabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (listener != null) {
                    MacroExecutor.execute(macros[idx][1],
                            listener::onSendToTerminal, v.getHandler());
                }
            });
            btn.setOnLongClickListener(v -> {
                if (hapticEnabled) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                MacroEditDialog.show(context, macros[idx][0], macros[idx][1],
                        (label, cmd) -> {
                            macros[idx][0] = label;
                            macros[idx][1] = cmd;
                            macroButtons[idx].setText(label);
                            MacroStore.save(context, macros);
                        });
                return true;
            });
            macroButtons[idx] = btn;
            row.addView(btn, buttonParams());
        }

        return row;
    }

    // --- Macro page (all visible, no paging) ---

    @Override
    public int getCurrentMacroPage() {
        return 0;
    }

    @Override
    public void setCurrentMacroPage(int page) {
        // All macros visible — reload labels in case macros were edited on another panel
        macros = MacroStore.load(getContext());
        for (int i = 0; i < 12; i++) {
            macroButtons[i].setText(macros[i][0]);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getContext().getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(hapticListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        PanelUtils.cancelRepeat(repeatState);
        getContext().getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(hapticListener);
    }

    // --- Modifier state API ---

    @Override
    public boolean isCtrlActive() {
        return ctrlActive;
    }

    @Override
    public void setCtrlActive(boolean active) {
        ctrlActive = active;
        updateModifierButtonColor(ctrlButton, ctrlActive);
    }

    @Override
    public void resetCtrl() {
        ctrlActive = false;
        updateModifierButtonColor(ctrlButton, false);
    }

    @Override
    public boolean isShiftActive() {
        return false;
    }

    @Override
    public void setShiftActive(boolean active) {
        // No shift button — dedicated S-TAB and S-ENT buttons instead
    }

    @Override
    public void resetShift() {
        // No shift button
    }

    // --- Layout helpers ---

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

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private void updateModifierButtonColor(Button btn, boolean active) {
        btn.setBackground(PanelUtils.makeCompactButtonDrawable(getContext(),
                active ? theme.active : theme.modifier));
    }

    private int dp(int value) {
        return PanelUtils.dp(getContext(), value);
    }
}
