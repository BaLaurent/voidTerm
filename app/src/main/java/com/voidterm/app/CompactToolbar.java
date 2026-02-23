package com.voidterm.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.voidterm.contracts.ControlPanel;
import com.voidterm.contracts.ControlPanelListener;

/**
 * Compact horizontal toolbar shown above the soft keyboard.
 * Page 1 (main): ESC, CTL, SHF, TAB, Up, Down, Enter, STT
 * Pages 2-4 (macros): back/page button + 4 macro buttons (swipe left to access, 3 pages)
 * Uses the same ControlPanelListener interface as GameBoyControlPanel.
 */
public class CompactToolbar extends FrameLayout implements ControlPanel {

    private static final int SWIPE_THRESHOLD_DP = 60;

    private final InterfaceTheme theme;
    private final int swipeThresholdPx;

    private ControlPanelListener listener;
    private boolean ctrlActive;
    private boolean shiftActive;
    private Button ctrlButton;
    private Button shiftButton;

    private LinearLayout mainRow;
    private LinearLayout macroRow;
    private boolean showingMacros = false;

    private float touchStartX;
    private float touchStartY;
    private boolean tracking = false;

    private String[][] macros;
    private final Button[] macroButtons = new Button[4];
    private int currentMacroPage = 0;
    private Button macroPageButton;

    private final PanelUtils.RepeatState repeatState = new PanelUtils.RepeatState();

    private volatile boolean hapticEnabled;
    private final SharedPreferences.OnSharedPreferenceChangeListener hapticListener =
            (prefs, key) -> {
                if (SettingsDialog.KEY_HAPTIC_FEEDBACK.equals(key)) {
                    hapticEnabled = prefs.getBoolean(SettingsDialog.KEY_HAPTIC_FEEDBACK, true);
                }
            };

    public CompactToolbar(Context context) {
        super(context);
        theme = InterfaceTheme.current(context);
        swipeThresholdPx = dp(SWIPE_THRESHOLD_DP);
        setBackgroundColor(theme.background);
        macros = MacroStore.load(context);

        SharedPreferences prefs = context.getSharedPreferences(
                SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE);
        hapticEnabled = prefs.getBoolean(SettingsDialog.KEY_HAPTIC_FEEDBACK, true);
        prefs.registerOnSharedPreferenceChangeListener(hapticListener);

        buildMainRow(context);
        buildMacroRow(context);
    }

    public void setControlPanelListener(ControlPanelListener listener) {
        this.listener = listener;
    }

    // --- Page building ---

    private void buildMainRow(Context context) {
        mainRow = new LinearLayout(context);
        mainRow.setOrientation(LinearLayout.HORIZONTAL);
        mainRow.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(4);
        mainRow.setPadding(pad, pad, pad, pad);

        // ESC
        PanelUtils.addCompactButton(mainRow, context, "ESC", theme.modifier, v -> {
            if (listener != null) listener.onSendToTerminal("\033");
        }, buttonParams(), () -> hapticEnabled);

        // CTL (sticky toggle)
        ctrlButton = PanelUtils.makeCompactButton(context, "CTL", theme.modifier);
        ctrlButton.setOnClickListener(v -> {
            if (hapticEnabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            ctrlActive = !ctrlActive;
            updateModifierButtonColor(ctrlButton, ctrlActive);
        });
        mainRow.addView(ctrlButton, buttonParams());

        // SHF (sticky toggle)
        shiftButton = PanelUtils.makeCompactButton(context, "SHF", theme.modifier);
        shiftButton.setOnClickListener(v -> {
            if (hapticEnabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            shiftActive = !shiftActive;
            updateModifierButtonColor(shiftButton, shiftActive);
        });
        mainRow.addView(shiftButton, buttonParams());

        // TAB (respects shift for backtab)
        PanelUtils.addCompactButton(mainRow, context, "TAB", theme.primary, v -> {
            if (listener != null) {
                if (shiftActive) {
                    listener.onSendToTerminal("\033[Z");
                    resetShift();
                } else {
                    listener.onSendToTerminal("\t");
                }
            }
        }, buttonParams(), () -> hapticEnabled);

        // Left arrow (with repeat)
        Button left = PanelUtils.makeCompactButton(context, "\u25C0", theme.dpad);
        PanelUtils.setupArrowRepeat(repeatState, left, "\033[D",
            text -> { if (listener != null) listener.onSendToTerminal(text); },
            () -> hapticEnabled);
        mainRow.addView(left, buttonParams());

        // Up arrow (with repeat)
        Button up = PanelUtils.makeCompactButton(context, "\u25B2", theme.dpad);
        PanelUtils.setupArrowRepeat(repeatState, up, "\033[A",
            text -> { if (listener != null) listener.onSendToTerminal(text); },
            () -> hapticEnabled);
        mainRow.addView(up, buttonParams());

        // Down arrow (with repeat)
        Button down = PanelUtils.makeCompactButton(context, "\u25BC", theme.dpad);
        PanelUtils.setupArrowRepeat(repeatState, down, "\033[B",
            text -> { if (listener != null) listener.onSendToTerminal(text); },
            () -> hapticEnabled);
        mainRow.addView(down, buttonParams());

        // Right arrow (with repeat)
        Button right = PanelUtils.makeCompactButton(context, "\u25B6", theme.dpad);
        PanelUtils.setupArrowRepeat(repeatState, right, "\033[C",
            text -> { if (listener != null) listener.onSendToTerminal(text); },
            () -> hapticEnabled);
        mainRow.addView(right, buttonParams());

        // Enter (respects shift for newline without submit)
        PanelUtils.addCompactButton(mainRow, context, "\u21B5", theme.primary, v -> {
            if (listener != null) {
                if (shiftActive) {
                    listener.onSendToTerminal("\n");
                    resetShift();
                } else {
                    listener.onSendToTerminal("\r");
                }
            }
        }, buttonParams(), () -> hapticEnabled);

        // STT (voice)
        PanelUtils.addCompactButton(mainRow, context, "\uD83C\uDFA4", theme.dpad, v -> {
            if (listener != null) listener.onVoiceToggle();
        }, buttonParams(), () -> hapticEnabled);

        // Burger menu (settings)
        PanelUtils.addCompactButton(mainRow, context, "\u2630", theme.macro, v -> {
            if (listener != null) listener.onSettingsRequested();
        }, buttonParams(), () -> hapticEnabled);

        addView(mainRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void buildMacroRow(Context context) {
        macroRow = new LinearLayout(context);
        macroRow.setOrientation(LinearLayout.HORIZONTAL);
        macroRow.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(4);
        macroRow.setPadding(pad, pad, pad, pad);
        macroRow.setVisibility(View.GONE);

        // Back button
        PanelUtils.addCompactButton(macroRow, context, "\u25C0", theme.dpad, v -> {
            if (currentMacroPage > 0) {
                currentMacroPage--;
                updateMacroPage();
            } else {
                showPage(false);
            }
        }, buttonParams(), () -> hapticEnabled);

        // Page indicator button
        macroPageButton = PanelUtils.makeCompactButton(context, "1/" + MacroStore.PAGE_COUNT, theme.dpad);
        macroPageButton.setOnClickListener(v -> {
            if (hapticEnabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            currentMacroPage = (currentMacroPage + 1) % MacroStore.PAGE_COUNT;
            updateMacroPage();
        });
        macroRow.addView(macroPageButton, buttonParams());

        // 4 macro buttons
        for (int i = 0; i < 4; i++) {
            Button btn = PanelUtils.makeCompactButton(context,
                    macros[currentMacroPage * MacroStore.PAGE_SIZE + i][0], theme.macro);
            final int index = i;
            btn.setOnClickListener(v -> {
                if (hapticEnabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (listener != null) {
                    int actualIndex = currentMacroPage * MacroStore.PAGE_SIZE + index;
                    MacroExecutor.execute(macros[actualIndex][1],
                            listener::onSendToTerminal, v.getHandler());
                }
            });
            btn.setOnLongClickListener(v -> {
                if (hapticEnabled) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                int actualIndex = currentMacroPage * MacroStore.PAGE_SIZE + index;
                MacroEditDialog.show(context, macros[actualIndex][0], macros[actualIndex][1],
                        (label, cmd) -> {
                            int ai = currentMacroPage * MacroStore.PAGE_SIZE + index;
                            macros[ai][0] = label;
                            macros[ai][1] = cmd;
                            macroButtons[index].setText(label);
                            MacroStore.save(context, macros);
                        });
                return true;
            });
            macroButtons[i] = btn;
            macroRow.addView(btn, buttonParams());
        }

        addView(macroRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // --- Page swapping ---

    private void showPage(boolean macros) {
        showingMacros = macros;
        if (macros) {
            reloadMacroLabels();
            mainRow.setVisibility(View.GONE);
            macroRow.setVisibility(View.VISIBLE);
        } else {
            macroRow.setVisibility(View.GONE);
            mainRow.setVisibility(View.VISIBLE);
        }
    }

    private void reloadMacroLabels() {
        this.macros = MacroStore.load(getContext());
        updateMacroPage();
    }

    @Override
    public int getCurrentMacroPage() {
        return currentMacroPage;
    }

    @Override
    public void setCurrentMacroPage(int page) {
        if (page >= 0 && page < MacroStore.PAGE_COUNT) {
            currentMacroPage = page;
            updateMacroPage();
        }
    }

    private void updateMacroPage() {
        PanelUtils.updateMacroPage(macroButtons, macroPageButton, macros, currentMacroPage);
    }

    // --- Swipe detection ---

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = ev.getX();
                touchStartY = ev.getY();
                tracking = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (tracking) {
                    float dx = ev.getX() - touchStartX;
                    float dy = ev.getY() - touchStartY;
                    if (Math.abs(dx) > swipeThresholdPx && Math.abs(dx) > Math.abs(dy) * 2) {
                        tracking = false;
                        if (dx < 0) {
                            // Swipe left
                            if (!showingMacros) {
                                currentMacroPage = 0;
                                showPage(true);
                            } else if (currentMacroPage < MacroStore.PAGE_COUNT - 1) {
                                currentMacroPage++;
                                updateMacroPage();
                            }
                        } else {
                            // Swipe right
                            if (showingMacros && currentMacroPage > 0) {
                                currentMacroPage--;
                                updateMacroPage();
                            } else if (showingMacros) {
                                showPage(false);
                            }
                        }
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    // --- Layout helpers ---

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        PanelUtils.cancelRepeat(repeatState);
        getContext().getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(hapticListener);
    }

    private void updateModifierButtonColor(Button btn, boolean active) {
        btn.setBackground(PanelUtils.makeCompactButtonDrawable(getContext(),
                active ? theme.active : theme.modifier));
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
        return shiftActive;
    }

    @Override
    public void setShiftActive(boolean active) {
        shiftActive = active;
        updateModifierButtonColor(shiftButton, shiftActive);
    }

    @Override
    public void resetShift() {
        shiftActive = false;
        updateModifierButtonColor(shiftButton, false);
    }

    // --- Utility ---

    private int dp(int value) {
        return PanelUtils.dp(getContext(), value);
    }
}
