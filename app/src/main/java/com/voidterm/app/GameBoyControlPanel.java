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
import android.widget.RelativeLayout;

/**
 * GameBoy-inspired control panel optimized for right-hand VR raycast usage.
 *
 * Layout (horizontal, right-hand ergonomic):
 *   Macros (weight=2):     4 macro buttons stacked vertically (far left, rarely used)
 *   Modifiers (weight=2):  ESC, CTL, SHF stacked vertically (center-left)
 *   D-pad (weight=6):      Arrow cross + STT center (center-right, most used)
 *   Primary (weight=2):    TAB + Enter stacked (far right, natural resting zone)
 *
 * Visual style: Nintendo Game Boy DMG-01 aesthetic.
 */
public class GameBoyControlPanel extends FrameLayout {

    private static final String TAG = "GameBoyControlPanel";

    // GameBoy DMG-01 color palette
    private static final int COLOR_DPAD        = 0xFF2B2B2B; // D-pad charcoal
    private static final int COLOR_DPAD_CROSS  = 0xFF1A1A1A; // D-pad cross cavity
    private static final int COLOR_PRIMARY     = 0xFF9B2257; // A/B wine-red
    private static final int COLOR_MODIFIER    = 0xFF3C3C6E; // Navy modifier
    private static final int COLOR_MACRO       = 0xFF585858; // Start/Select gray
    private static final int COLOR_ACTIVE      = 0xFF9BBC0F; // GameBoy screen green

    private ControlPanelListener listener;
    private final Button[] macroButtons = new Button[4];
    private String[][] macros; // [i][0]=label, [i][1]=cmd
    private int currentPage = 0;
    private Button pageButton;

    private boolean ctrlActive;
    private boolean shiftActive;
    private Button ctrlButton;
    private Button shiftButton;

    public interface ControlPanelListener {
        void onSendToTerminal(String text);
        void onVoiceToggle();
        void onSettingsRequested();
    }

    public GameBoyControlPanel(Context context) {
        super(context);
        macros = MacroStore.load(context);
        buildLayout(context);
    }

    public void setControlPanelListener(ControlPanelListener listener) {
        this.listener = listener;
    }

    private void buildLayout(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(8);
        root.setPadding(pad, pad, pad, pad);
        addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Burger menu button — bottom center (space bar position)
        Button menuBtn = makeButton(context, dp(36), "\u2630", 16f, COLOR_MACRO, true);
        menuBtn.setPadding(dp(24), 0, dp(24), 0);
        menuBtn.setMinWidth(dp(80));
        menuBtn.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) listener.onSettingsRequested();
        });
        FrameLayout.LayoutParams menuLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        menuLp.bottomMargin = dp(4);
        addView(menuBtn, menuLp);

        // Far left: macros (weight=2, rarely used)
        root.addView(buildMacroZone(context), weightParams(2f));

        // Center-left: modifiers (weight=2, before keyboard input)
        root.addView(buildModifierZone(context), weightParams(2f));

        // Center-right: D-pad (weight=6, most used — needs room for cross)
        root.addView(buildDpadZone(context), weightParams(6f));

        // Far right: primary actions (weight=2, natural resting zone)
        root.addView(buildPrimaryZone(context), weightParams(2f));
    }

    // --- Macro Zone (left): pill-shaped Start/Select style ---

    private View buildMacroZone(Context context) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);

        int spacing = dp(4);

        // Page cycle button at top
        pageButton = makeButton(context, dp(24), "1/3", 8f, COLOR_MACRO, true);
        pageButton.setPadding(dp(8), 0, dp(8), 0);
        pageButton.setMinWidth(dp(36));
        pageButton.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            currentPage = (currentPage + 1) % MacroStore.PAGE_COUNT;
            updateMacroPage();
        });
        LinearLayout.LayoutParams pageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(24));
        pageLp.gravity = Gravity.CENTER_HORIZONTAL;
        pageLp.bottomMargin = spacing;
        col.addView(pageButton, pageLp);

        for (int i = 0; i < 4; i++) {
            Button btn = makeButton(context, dp(32), macros[currentPage * MacroStore.PAGE_SIZE + i][0], 10f, COLOR_MACRO, true);
            btn.setPadding(dp(12), 0, dp(12), 0);
            btn.setMinWidth(dp(40));
            final int index = i;

            btn.setOnClickListener(v -> {
                if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (listener != null) {
                    int actualIndex = currentPage * MacroStore.PAGE_SIZE + index;
                    MacroExecutor.execute(macros[actualIndex][1],
                            listener::onSendToTerminal, v.getHandler());
                }
            });
            btn.setOnLongClickListener(v -> {
                if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                int actualIndex = currentPage * MacroStore.PAGE_SIZE + index;
                MacroEditDialog.show(context, macros[actualIndex][0], macros[actualIndex][1],
                        (label, cmd) -> {
                            int ai = currentPage * MacroStore.PAGE_SIZE + index;
                            macros[ai][0] = label;
                            macros[ai][1] = cmd;
                            macroButtons[index].setText(label);
                            MacroStore.save(context, macros);
                        });
                return true;
            });

            macroButtons[i] = btn;

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.topMargin = spacing;
            col.addView(btn, lp);
        }
        return col;
    }

    private void updateMacroPage() {
        for (int i = 0; i < MacroStore.PAGE_SIZE; i++) {
            macroButtons[i].setText(macros[currentPage * MacroStore.PAGE_SIZE + i][0]);
        }
        pageButton.setText((currentPage + 1) + "/" + MacroStore.PAGE_COUNT);
    }

    // --- D-pad Zone (center-right): cross cavity + arrows around STT center ---

    private View buildDpadZone(Context context) {
        RelativeLayout dpad = new RelativeLayout(context);

        int arrowSize = dp(48);
        int sttSize = dp(52);
        int gap = dp(4);

        // D-pad cross cavity background (horizontal + vertical bars)
        View hBar = new View(context);
        GradientDrawable hBg = new GradientDrawable();
        hBg.setShape(GradientDrawable.RECTANGLE);
        hBg.setCornerRadius(dp(6));
        hBg.setColor(COLOR_DPAD_CROSS);
        hBar.setBackground(hBg);
        RelativeLayout.LayoutParams hLp = new RelativeLayout.LayoutParams(dp(148), arrowSize);
        hLp.addRule(RelativeLayout.CENTER_IN_PARENT);
        dpad.addView(hBar, hLp);

        View vBar = new View(context);
        GradientDrawable vBg = new GradientDrawable();
        vBg.setShape(GradientDrawable.RECTANGLE);
        vBg.setCornerRadius(dp(6));
        vBg.setColor(COLOR_DPAD_CROSS);
        vBar.setBackground(vBg);
        RelativeLayout.LayoutParams vLp = new RelativeLayout.LayoutParams(arrowSize, dp(148));
        vLp.addRule(RelativeLayout.CENTER_IN_PARENT);
        dpad.addView(vBar, vLp);

        // STT center button — anchor for the cross
        Button stt = makeButton(context, sttSize, "\uD83C\uDFA4", 18f, COLOR_DPAD, false);
        int sttId = View.generateViewId();
        stt.setId(sttId);
        stt.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) listener.onVoiceToggle();
        });
        RelativeLayout.LayoutParams sttLp = new RelativeLayout.LayoutParams(sttSize, sttSize);
        sttLp.addRule(RelativeLayout.CENTER_IN_PARENT);
        dpad.addView(stt, sttLp);

        // UP — above STT
        Button up = makeButton(context, arrowSize, "\u25B2", 14f, COLOR_DPAD, false);
        setupArrowRepeat(up, "\033[A");
        RelativeLayout.LayoutParams upLp = new RelativeLayout.LayoutParams(arrowSize, arrowSize);
        upLp.addRule(RelativeLayout.ABOVE, sttId);
        upLp.addRule(RelativeLayout.CENTER_HORIZONTAL);
        upLp.bottomMargin = gap;
        dpad.addView(up, upLp);

        // DOWN — below STT
        Button down = makeButton(context, arrowSize, "\u25BC", 14f, COLOR_DPAD, false);
        setupArrowRepeat(down, "\033[B");
        RelativeLayout.LayoutParams downLp = new RelativeLayout.LayoutParams(arrowSize, arrowSize);
        downLp.addRule(RelativeLayout.BELOW, sttId);
        downLp.addRule(RelativeLayout.CENTER_HORIZONTAL);
        downLp.topMargin = gap;
        dpad.addView(down, downLp);

        // LEFT — left of STT
        Button left = makeButton(context, arrowSize, "\u25C0", 14f, COLOR_DPAD, false);
        setupArrowRepeat(left, "\033[D");
        RelativeLayout.LayoutParams leftLp = new RelativeLayout.LayoutParams(arrowSize, arrowSize);
        leftLp.addRule(RelativeLayout.LEFT_OF, sttId);
        leftLp.addRule(RelativeLayout.CENTER_VERTICAL);
        leftLp.rightMargin = gap;
        dpad.addView(left, leftLp);

        // RIGHT — right of STT
        Button right = makeButton(context, arrowSize, "\u25B6", 14f, COLOR_DPAD, false);
        setupArrowRepeat(right, "\033[C");
        RelativeLayout.LayoutParams rightLp = new RelativeLayout.LayoutParams(arrowSize, arrowSize);
        rightLp.addRule(RelativeLayout.RIGHT_OF, sttId);
        rightLp.addRule(RelativeLayout.CENTER_VERTICAL);
        rightLp.leftMargin = gap;
        dpad.addView(right, rightLp);

        return dpad;
    }

    private void setupArrowRepeat(Button btn, String escSeq) {
        btn.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (listener != null) listener.onSendToTerminal(escSeq);
                    v.setPressed(true);
                    // Start repeating
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

    // --- Modifier Zone (center-left): ESC, CTL, SHF stacked vertically ---

    private View buildModifierZone(Context context) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);

        int btnSize = dp(40);
        int spacing = dp(4);

        // ESC
        Button esc = makeButton(context, btnSize, "ESC", 9f, COLOR_MODIFIER, false);
        esc.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) listener.onSendToTerminal("\033");
        });
        LinearLayout.LayoutParams escLp = new LinearLayout.LayoutParams(btnSize, btnSize);
        escLp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(esc, escLp);

        // CTL (sticky toggle)
        ctrlButton = makeButton(context, btnSize, "CTL", 9f, COLOR_MODIFIER, false);
        ctrlButton.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            ctrlActive = !ctrlActive;
            updateButtonColor(ctrlButton, ctrlActive);
        });
        LinearLayout.LayoutParams ctlLp = new LinearLayout.LayoutParams(btnSize, btnSize);
        ctlLp.gravity = Gravity.CENTER_HORIZONTAL;
        ctlLp.topMargin = spacing;
        col.addView(ctrlButton, ctlLp);

        // SHF (sticky toggle)
        shiftButton = makeButton(context, btnSize, "SHF", 9f, COLOR_MODIFIER, false);
        shiftButton.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            shiftActive = !shiftActive;
            updateButtonColor(shiftButton, shiftActive);
        });
        LinearLayout.LayoutParams shfLp = new LinearLayout.LayoutParams(btnSize, btnSize);
        shfLp.gravity = Gravity.CENTER_HORIZONTAL;
        shfLp.topMargin = spacing;
        col.addView(shiftButton, shfLp);

        return col;
    }

    // --- Primary Zone (far right): TAB + Enter (wine-red A/B style) ---

    private View buildPrimaryZone(Context context) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);

        int spacing = dp(6);

        // S-TAB (Shift+Tab / backtab) — 40dp
        int sTabSize = dp(40);
        Button sTab = makeButton(context, sTabSize, "S-TAB", 8f, COLOR_MODIFIER, false);
        sTab.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) listener.onSendToTerminal("\033[Z");
        });
        LinearLayout.LayoutParams sTabLp = new LinearLayout.LayoutParams(sTabSize, sTabSize);
        sTabLp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(sTab, sTabLp);

        // TAB — 48dp, matches arrow size
        int tabSize = dp(48);
        Button tab = makeButton(context, tabSize, "TAB", 10f, COLOR_PRIMARY, false);
        tab.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) {
                if (shiftActive) {
                    listener.onSendToTerminal("\033[Z");
                    resetShift();
                } else {
                    listener.onSendToTerminal("\t");
                }
            }
        });
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(tabSize, tabSize);
        tabLp.gravity = Gravity.CENTER_HORIZONTAL;
        tabLp.topMargin = spacing;
        col.addView(tab, tabLp);

        // Enter — 52dp, largest button in the panel
        int enterSize = dp(52);
        Button enter = makeButton(context, enterSize, "\u21B5", 18f, COLOR_PRIMARY, false);
        enter.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) listener.onSendToTerminal("\r");
        });
        LinearLayout.LayoutParams enterLp = new LinearLayout.LayoutParams(enterSize, enterSize);
        enterLp.gravity = Gravity.CENTER_HORIZONTAL;
        enterLp.topMargin = spacing;
        col.addView(enter, enterLp);

        // S-Enter (Shift+Enter / newline without submit) — 40dp
        int sEnterSize = dp(40);
        Button sEnter = makeButton(context, sEnterSize, "S-\u21B5", 8f, COLOR_MODIFIER, false);
        sEnter.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) listener.onSendToTerminal("\n");
        });
        LinearLayout.LayoutParams sEnterLp = new LinearLayout.LayoutParams(sEnterSize, sEnterSize);
        sEnterLp.gravity = Gravity.CENTER_HORIZONTAL;
        sEnterLp.topMargin = spacing;
        col.addView(sEnter, sEnterLp);

        return col;
    }

    private void updateButtonColor(Button btn, boolean active) {
        int color = active ? COLOR_ACTIVE : COLOR_MODIFIER;
        btn.setBackground(makeButtonDrawable(color, 0, false));
    }

    // --- Modifier state (read by VoidTermTerminalViewClient) ---

    public boolean isCtrlActive() {
        return ctrlActive;
    }

    public boolean isShiftActive() {
        return shiftActive;
    }

    public void resetCtrl() {
        ctrlActive = false;
        if (ctrlButton != null) updateButtonColor(ctrlButton, false);
    }

    public void resetShift() {
        shiftActive = false;
        if (shiftButton != null) updateButtonColor(shiftButton, false);
    }

    // --- Button factory ---

    private Button makeButton(Context ctx, int size, String label, float textSp, int bgColor, boolean pill) {
        Button btn = new Button(ctx);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setAllCaps(false);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(0, 0, 0, 0);
        btn.setMinWidth(0);
        btn.setMinHeight(0);
        btn.setMinimumWidth(0);
        btn.setMinimumHeight(0);

        btn.setBackground(makeButtonDrawable(bgColor, size, pill));

        btn.setStateListAnimator(null);
        return btn;
    }

    private StateListDrawable makeButtonDrawable(int bgColor, int size, boolean pill) {
        // Normal state: convex (light top → dark bottom)
        GradientDrawable normal = makeGradientShape(bgColor, size, pill);
        normal.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        normal.setColors(new int[]{lightenColor(bgColor, 1.4f), darkenColor(bgColor, 0.85f)});
        normal.setStroke(dp(2), darkenColor(bgColor, 0.55f));

        // Pressed state: concave (dark top → dark bottom, pushed-in look)
        GradientDrawable pressed = makeGradientShape(bgColor, size, pill);
        pressed.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        pressed.setColors(new int[]{darkenColor(bgColor, 0.6f), darkenColor(bgColor, 0.8f)});
        pressed.setStroke(dp(2), darkenColor(bgColor, 0.4f));

        StateListDrawable stateList = new StateListDrawable();
        stateList.addState(new int[]{android.R.attr.state_pressed}, pressed);
        stateList.addState(new int[]{}, normal);
        return stateList;
    }

    private GradientDrawable makeGradientShape(int bgColor, int size, boolean pill) {
        GradientDrawable shape = new GradientDrawable();
        if (pill) {
            shape.setShape(GradientDrawable.RECTANGLE);
            shape.setCornerRadius(size / 2f);
        } else {
            shape.setShape(GradientDrawable.OVAL);
        }
        return shape;
    }

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

    private LinearLayout.LayoutParams weightParams(float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        return lp;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

}
