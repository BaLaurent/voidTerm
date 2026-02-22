package com.voidterm.app;

import android.content.Context;
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
 * Compact horizontal toolbar shown above the soft keyboard.
 * Page 1 (main): ESC, CTL, SHF, TAB, Up, Down, Enter, STT
 * Pages 2-4 (macros): back/page button + 4 macro buttons (swipe left to access, 3 pages)
 * Uses the same ControlPanelListener interface as GameBoyControlPanel.
 */
public class CompactToolbar extends FrameLayout {

    private static final int SWIPE_THRESHOLD_DP = 60;

    private final InterfaceTheme theme;

    private GameBoyControlPanel.ControlPanelListener listener;
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

    private Runnable activeRepeatRunnable;
    private View activeRepeatView;

    public CompactToolbar(Context context) {
        super(context);
        theme = InterfaceTheme.current(context);
        setBackgroundColor(theme.background);
        macros = MacroStore.load(context);
        buildMainRow(context);
        buildMacroRow(context);
    }

    public void setControlPanelListener(GameBoyControlPanel.ControlPanelListener listener) {
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
        addToolbarButton(mainRow, context, "ESC", theme.modifier, v -> {
            if (listener != null) listener.onSendToTerminal("\033");
        });

        // CTL (sticky toggle)
        ctrlButton = makeButton(context, "CTL", theme.modifier);
        ctrlButton.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            ctrlActive = !ctrlActive;
            updateModifierButtonColor(ctrlButton, ctrlActive);
        });
        mainRow.addView(ctrlButton, buttonParams());

        // SHF (sticky toggle)
        shiftButton = makeButton(context, "SHF", theme.modifier);
        shiftButton.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            shiftActive = !shiftActive;
            updateModifierButtonColor(shiftButton, shiftActive);
        });
        mainRow.addView(shiftButton, buttonParams());

        // TAB (respects shift for backtab)
        addToolbarButton(mainRow, context, "TAB", theme.primary, v -> {
            if (listener != null) {
                if (shiftActive) {
                    listener.onSendToTerminal("\033[Z");
                    resetShift();
                } else {
                    listener.onSendToTerminal("\t");
                }
            }
        });

        // Left arrow (with repeat)
        Button left = makeButton(context, "\u25C0", theme.dpad);
        setupArrowRepeat(left, "\033[D");
        mainRow.addView(left, buttonParams());

        // Up arrow (with repeat)
        Button up = makeButton(context, "\u25B2", theme.dpad);
        setupArrowRepeat(up, "\033[A");
        mainRow.addView(up, buttonParams());

        // Down arrow (with repeat)
        Button down = makeButton(context, "\u25BC", theme.dpad);
        setupArrowRepeat(down, "\033[B");
        mainRow.addView(down, buttonParams());

        // Right arrow (with repeat)
        Button right = makeButton(context, "\u25B6", theme.dpad);
        setupArrowRepeat(right, "\033[C");
        mainRow.addView(right, buttonParams());

        // Enter (respects shift for newline without submit)
        addToolbarButton(mainRow, context, "\u21B5", theme.primary, v -> {
            if (listener != null) {
                if (shiftActive) {
                    listener.onSendToTerminal("\n");
                    resetShift();
                } else {
                    listener.onSendToTerminal("\r");
                }
            }
        });

        // STT (voice)
        addToolbarButton(mainRow, context, "\uD83C\uDFA4", theme.dpad, v -> {
            if (listener != null) listener.onVoiceToggle();
        });

        // Burger menu (settings)
        addToolbarButton(mainRow, context, "\u2630", theme.macro, v -> {
            if (listener != null) listener.onSettingsRequested();
        });

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
        addToolbarButton(macroRow, context, "\u25C0", theme.dpad, v -> {
            if (currentMacroPage > 0) {
                currentMacroPage--;
                updateMacroPage();
            } else {
                showPage(false);
            }
        });

        // Page indicator button
        macroPageButton = makeButton(context, "1/" + MacroStore.PAGE_COUNT, theme.dpad);
        macroPageButton.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            currentMacroPage = (currentMacroPage + 1) % MacroStore.PAGE_COUNT;
            updateMacroPage();
        });
        macroRow.addView(macroPageButton, buttonParams());

        // 4 macro buttons
        for (int i = 0; i < 4; i++) {
            Button btn = makeButton(context, macros[currentMacroPage * MacroStore.PAGE_SIZE + i][0], theme.macro);
            final int index = i;
            btn.setOnClickListener(v -> {
                if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (listener != null) {
                    int actualIndex = currentMacroPage * MacroStore.PAGE_SIZE + index;
                    MacroExecutor.execute(macros[actualIndex][1],
                            listener::onSendToTerminal, v.getHandler());
                }
            });
            btn.setOnLongClickListener(v -> {
                if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
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
        for (int i = 0; i < MacroStore.PAGE_SIZE; i++) {
            macroButtons[i].setText(macros[currentMacroPage * MacroStore.PAGE_SIZE + i][0]);
        }
        macroPageButton.setText((currentMacroPage + 1) + "/" + MacroStore.PAGE_COUNT);
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
                    int threshold = dp(SWIPE_THRESHOLD_DP);
                    if (Math.abs(dx) > threshold && Math.abs(dx) > Math.abs(dy) * 2) {
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

    // --- Button helpers ---

    private void addToolbarButton(LinearLayout row, Context context, String label, int color, OnClickListener click) {
        Button btn = makeButton(context, label, color);
        btn.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
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
            default: return label;
        }
    }

    private StateListDrawable makeButtonDrawable(int bgColor) {
        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.RECTANGLE);
        normal.setCornerRadius(dp(6));
        normal.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        normal.setColors(new int[]{InterfaceTheme.lightenColor(bgColor, 1.4f), InterfaceTheme.darkenColor(bgColor, 0.85f)});
        normal.setStroke(dp(1), InterfaceTheme.darkenColor(bgColor, 0.55f));

        GradientDrawable pressed = new GradientDrawable();
        pressed.setShape(GradientDrawable.RECTANGLE);
        pressed.setCornerRadius(dp(6));
        pressed.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        pressed.setColors(new int[]{InterfaceTheme.darkenColor(bgColor, 0.6f), InterfaceTheme.darkenColor(bgColor, 0.8f)});
        pressed.setStroke(dp(1), InterfaceTheme.darkenColor(bgColor, 0.4f));

        StateListDrawable stateList = new StateListDrawable();
        stateList.addState(new int[]{android.R.attr.state_pressed}, pressed);
        stateList.addState(new int[]{}, normal);
        return stateList;
    }

    private void setupArrowRepeat(Button btn, String escSeq) {
        btn.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
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
    }

    private void updateModifierButtonColor(Button btn, boolean active) {
        btn.setBackground(makeButtonDrawable(active ? theme.active : theme.modifier));
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

    // --- Utility ---

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
