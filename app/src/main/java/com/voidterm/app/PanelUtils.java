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
import android.widget.LinearLayout;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Shared utility methods for GameBoyControlPanel, CompactToolbar, and CompactPanel.
 * Consolidates duplicated logic: dp conversion, accessibility descriptions,
 * arrow key repeat, and macro page updates.
 */
public final class PanelUtils {

    /** Mutable state for arrow key repeat. Each panel owns one instance. */
    public static final class RepeatState {
        Runnable activeRepeatRunnable;
        View activeRepeatView;
    }

    public static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    public static String descriptionForLabel(String label) {
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

    /**
     * Configure arrow key auto-repeat on a button.
     * Sends escSeq immediately on press, then repeats every 100ms after 400ms hold.
     *
     * @param state       mutable repeat state owned by the panel
     * @param btn         the arrow button
     * @param escSeq      terminal escape sequence to send
     * @param onSend      callback to send text (reads listener dynamically via closure)
     * @param hapticCheck returns true if haptic feedback is enabled (reads field via closure)
     */
    public static void setupArrowRepeat(RepeatState state, View btn,
                                         String escSeq, Consumer<String> onSend,
                                         BooleanSupplier hapticCheck) {
        btn.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (hapticCheck.getAsBoolean()) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    }
                    onSend.accept(escSeq);
                    v.setPressed(true);
                    cancelRepeat(state);
                    Runnable repeat = new Runnable() {
                        @Override
                        public void run() {
                            if (v.isPressed()) {
                                onSend.accept(escSeq);
                                v.postDelayed(this, 100);
                            }
                        }
                    };
                    state.activeRepeatRunnable = repeat;
                    state.activeRepeatView = v;
                    v.postDelayed(repeat, 400);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    cancelRepeat(state);
                    return true;
            }
            return false;
        });
    }

    public static void cancelRepeat(RepeatState state) {
        if (state.activeRepeatRunnable != null && state.activeRepeatView != null) {
            state.activeRepeatView.removeCallbacks(state.activeRepeatRunnable);
        }
        state.activeRepeatRunnable = null;
        state.activeRepeatView = null;
    }

    /**
     * Update macro button labels and page indicator for the current page.
     *
     * @param macroButtons the 4 macro buttons on this page
     * @param pageButton   the page indicator button (e.g. "1/3")
     * @param macros       full macro array [12][2] (label, command)
     * @param page         current page index (0-based)
     */
    public static void updateMacroPage(Button[] macroButtons, Button pageButton,
                                        String[][] macros, int page) {
        for (int i = 0; i < MacroStore.PAGE_SIZE; i++) {
            macroButtons[i].setText(macros[page * MacroStore.PAGE_SIZE + i][0]);
        }
        pageButton.setText((page + 1) + "/" + MacroStore.PAGE_COUNT);
    }

    // --- Compact button factory (shared by CompactPanel and CompactToolbar) ---

    /**
     * Create a compact rectangular button (11sp text, dp(6) corner radius, dp(1) stroke).
     * Used by CompactPanel and CompactToolbar. GameBoy uses its own factory (oval/pill shapes).
     */
    public static Button makeCompactButton(Context ctx, String label, int bgColor) {
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
        btn.setBackground(makeCompactButtonDrawable(ctx, bgColor));
        btn.setStateListAnimator(null);
        btn.setContentDescription(descriptionForLabel(label));
        return btn;
    }

    /**
     * Create a StateListDrawable for compact rectangular buttons (dp(6) corner, dp(1) stroke).
     */
    public static StateListDrawable makeCompactButtonDrawable(Context ctx, int bgColor) {
        int cornerRadius = dp(ctx, 6);
        int strokeWidth = dp(ctx, 1);

        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.RECTANGLE);
        normal.setCornerRadius(cornerRadius);
        normal.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        normal.setColors(new int[]{InterfaceTheme.lightenColor(bgColor, 1.4f),
                InterfaceTheme.darkenColor(bgColor, 0.85f)});
        normal.setStroke(strokeWidth, InterfaceTheme.darkenColor(bgColor, 0.55f));

        GradientDrawable pressed = new GradientDrawable();
        pressed.setShape(GradientDrawable.RECTANGLE);
        pressed.setCornerRadius(cornerRadius);
        pressed.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        pressed.setColors(new int[]{InterfaceTheme.darkenColor(bgColor, 0.6f),
                InterfaceTheme.darkenColor(bgColor, 0.8f)});
        pressed.setStroke(strokeWidth, InterfaceTheme.darkenColor(bgColor, 0.4f));

        StateListDrawable stateList = new StateListDrawable();
        stateList.addState(new int[]{android.R.attr.state_pressed}, pressed);
        stateList.addState(new int[]{}, normal);
        return stateList;
    }

    /**
     * Create a compact button with haptic feedback and click listener, and add it to a row.
     */
    public static void addCompactButton(LinearLayout row, Context ctx, String label,
                                         int color, View.OnClickListener click,
                                         LinearLayout.LayoutParams params,
                                         BooleanSupplier hapticCheck) {
        Button btn = makeCompactButton(ctx, label, color);
        btn.setOnClickListener(v -> {
            if (hapticCheck.getAsBoolean()) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
            click.onClick(v);
        });
        row.addView(btn, params);
    }

    private PanelUtils() {}
}
