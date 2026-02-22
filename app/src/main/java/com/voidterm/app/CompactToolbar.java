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

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Compact horizontal toolbar shown above the soft keyboard.
 * Page 1 (main): ESC, CTL, SHF, TAB, Up, Down, Enter, STT
 * Page 2 (macros): back arrow + 4 macro buttons (swipe left to access)
 * Uses the same ControlPanelListener interface as GameBoyControlPanel.
 */
public class CompactToolbar extends FrameLayout {

    private static final int COLOR_DPAD     = 0xFF2B2B2B;
    private static final int COLOR_PRIMARY  = 0xFF9B2257;
    private static final int COLOR_MODIFIER = 0xFF3C3C6E;
    private static final int COLOR_MACRO    = 0xFF585858;
    private static final int COLOR_ACTIVE   = 0xFF9BBC0F;

    private static final String MACROS_PREFS = "voidterm_macros";
    private static final String MACROS_KEY = "macros";
    private static final String[][] DEFAULT_MACROS = {
            {"/clear", "clear"},
            {"/compact", "export TERM_COMPACT=1"},
            {"macro3", "echo 3"},
            {"macro4", "echo 4"},
    };

    private static final int SWIPE_THRESHOLD_DP = 60;

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

    public CompactToolbar(Context context) {
        super(context);
        setBackgroundColor(0xFFC4C4B4);
        macros = loadMacros(context);
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
        addToolbarButton(mainRow, context, "ESC", COLOR_MODIFIER, v -> {
            if (listener != null) listener.onSendToTerminal("\033");
        });

        // CTL (sticky toggle)
        ctrlButton = makeButton(context, "CTL", COLOR_MODIFIER);
        ctrlButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            ctrlActive = !ctrlActive;
            updateModifierButtonColor(ctrlButton, ctrlActive);
        });
        mainRow.addView(ctrlButton, buttonParams());

        // SHF (sticky toggle)
        shiftButton = makeButton(context, "SHF", COLOR_MODIFIER);
        shiftButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            shiftActive = !shiftActive;
            updateModifierButtonColor(shiftButton, shiftActive);
        });
        mainRow.addView(shiftButton, buttonParams());

        // TAB (respects shift for backtab)
        addToolbarButton(mainRow, context, "TAB", COLOR_PRIMARY, v -> {
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
        Button left = makeButton(context, "\u25C0", COLOR_DPAD);
        setupArrowRepeat(left, "\033[D");
        mainRow.addView(left, buttonParams());

        // Up arrow (with repeat)
        Button up = makeButton(context, "\u25B2", COLOR_DPAD);
        setupArrowRepeat(up, "\033[A");
        mainRow.addView(up, buttonParams());

        // Down arrow (with repeat)
        Button down = makeButton(context, "\u25BC", COLOR_DPAD);
        setupArrowRepeat(down, "\033[B");
        mainRow.addView(down, buttonParams());

        // Right arrow (with repeat)
        Button right = makeButton(context, "\u25B6", COLOR_DPAD);
        setupArrowRepeat(right, "\033[C");
        mainRow.addView(right, buttonParams());

        // Enter (respects shift for newline without submit)
        addToolbarButton(mainRow, context, "\u21B5", COLOR_PRIMARY, v -> {
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
        addToolbarButton(mainRow, context, "\uD83C\uDFA4", COLOR_DPAD, v -> {
            if (listener != null) listener.onVoiceToggle();
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
        addToolbarButton(macroRow, context, "\u25C0", COLOR_DPAD, v -> showPage(false));

        // 4 macro buttons
        for (int i = 0; i < 4; i++) {
            Button btn = makeButton(context, macros[i][0], COLOR_MACRO);
            final int index = i;
            btn.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (listener != null) {
                    MacroExecutor.execute(macros[index][1],
                            listener::onSendToTerminal, v.getHandler());
                }
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
        this.macros = loadMacros(getContext());
        for (int i = 0; i < 4; i++) {
            macroButtons[i].setText(macros[i][0]);
        }
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
                        if (dx < 0 && !showingMacros) {
                            showPage(true);
                        } else if (dx > 0 && showingMacros) {
                            showPage(false);
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
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
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
        return btn;
    }

    private StateListDrawable makeButtonDrawable(int bgColor) {
        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.RECTANGLE);
        normal.setCornerRadius(dp(6));
        normal.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        normal.setColors(new int[]{lightenColor(bgColor, 1.4f), darkenColor(bgColor, 0.85f)});
        normal.setStroke(dp(1), darkenColor(bgColor, 0.55f));

        GradientDrawable pressed = new GradientDrawable();
        pressed.setShape(GradientDrawable.RECTANGLE);
        pressed.setCornerRadius(dp(6));
        pressed.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        pressed.setColors(new int[]{darkenColor(bgColor, 0.6f), darkenColor(bgColor, 0.8f)});
        pressed.setStroke(dp(1), darkenColor(bgColor, 0.4f));

        StateListDrawable stateList = new StateListDrawable();
        stateList.addState(new int[]{android.R.attr.state_pressed}, pressed);
        stateList.addState(new int[]{}, normal);
        return stateList;
    }

    private void setupArrowRepeat(Button btn, String escSeq) {
        btn.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (listener != null) listener.onSendToTerminal(escSeq);
                    v.setPressed(true);
                    v.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (v.isPressed()) {
                                if (listener != null) listener.onSendToTerminal(escSeq);
                                v.postDelayed(this, 100);
                            }
                        }
                    }, 400);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    return true;
            }
            return false;
        });
    }

    private void updateModifierButtonColor(Button btn, boolean active) {
        btn.setBackground(makeButtonDrawable(active ? COLOR_ACTIVE : COLOR_MODIFIER));
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

    // --- Macro persistence (shared with GameBoyControlPanel) ---

    private String[][] loadMacros(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(MACROS_PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(MACROS_KEY, null);
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                if (arr.length() == 4) {
                    String[][] result = new String[4][2];
                    for (int i = 0; i < 4; i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        result[i][0] = obj.getString("label");
                        result[i][1] = obj.getString("cmd");
                    }
                    return result;
                }
            } catch (Exception ignored) {
            }
        }
        String[][] result = new String[4][2];
        for (int i = 0; i < 4; i++) {
            result[i][0] = DEFAULT_MACROS[i][0];
            result[i][1] = DEFAULT_MACROS[i][1];
        }
        return result;
    }

    // --- Utility ---

    private int darkenColor(int color, float factor) {
        int r = (int) (Color.red(color) * factor);
        int g = (int) (Color.green(color) * factor);
        int b = (int) (Color.blue(color) * factor);
        return Color.argb(Color.alpha(color), r, g, b);
    }

    private int lightenColor(int color, float factor) {
        int r = Math.min(255, (int) (Color.red(color) * factor));
        int g = Math.min(255, (int) (Color.green(color) * factor));
        int b = Math.min(255, (int) (Color.blue(color) * factor));
        return Color.argb(Color.alpha(color), r, g, b);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
