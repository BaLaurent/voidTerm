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
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import java.util.LinkedHashMap;
import java.util.Map;

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
 *
 * Supports a custom layout mode where buttons are freely positioned via drag & drop.
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

    private final Map<String, View> buttonRegistry = new LinkedHashMap<>();
    private LayoutEditMode editMode;

    public interface ControlPanelListener {
        void onSendToTerminal(String text);
        void onVoiceToggle();
        void onSettingsRequested();
    }

    public GameBoyControlPanel(Context context) {
        super(context);
        macros = MacroStore.load(context);
        Map<String, float[]> positions = LayoutStore.load(context);
        if (positions != null) {
            buildCustomLayout(context, positions);
        } else {
            buildLayout(context);
        }
    }

    public void setControlPanelListener(ControlPanelListener listener) {
        this.listener = listener;
    }

    // --- Default layout (weighted LinearLayout hierarchy) ---

    private void buildLayout(Context context) {
        buttonRegistry.clear();

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(8);
        root.setPadding(pad, pad, pad, pad);
        addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Burger menu button — bottom center (space bar position)
        Button menuBtn = createMenuButton(context);
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

    // --- Custom layout (absolute positioning from stored percentages) ---

    private void buildCustomLayout(Context context, Map<String, float[]> positions) {
        buttonRegistry.clear();

        createMenuButton(context);
        createPageButton(context);
        for (int i = 0; i < 4; i++) createMacroButton(context, i);
        createEscButton(context);
        createCtrlButton(context);
        createShiftButton(context);
        createSttButton(context);
        createArrowButton(context, "arrow_up", "\u25B2", "\033[A");
        createArrowButton(context, "arrow_down", "\u25BC", "\033[B");
        createArrowButton(context, "arrow_left", "\u25C0", "\033[D");
        createArrowButton(context, "arrow_right", "\u25B6", "\033[C");
        createSTabButton(context);
        createTabButton(context);
        createEnterButton(context);
        createSEnterButton(context);

        for (Map.Entry<String, View> entry : buttonRegistry.entrySet()) {
            View btn = entry.getValue();
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    getButtonWidth(entry.getKey()),
                    getButtonHeight(entry.getKey()));
            addView(btn, lp);
        }

        getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                getViewTreeObserver().removeOnGlobalLayoutListener(this);
                int pw = getWidth();
                int ph = getHeight();
                if (pw == 0 || ph == 0) return;
                for (Map.Entry<String, View> entry : buttonRegistry.entrySet()) {
                    float[] pos = positions.get(entry.getKey());
                    if (pos != null) {
                        View btn = entry.getValue();
                        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) btn.getLayoutParams();
                        lp.leftMargin = (int) (pos[0] * pw);
                        lp.topMargin = (int) (pos[1] * ph);
                        btn.setLayoutParams(lp);
                    }
                }
            }
        });
    }

    // --- Edit mode ---

    public void enterEditMode() {
        if (editMode != null) return;
        post(() -> {
            int pw = getWidth();
            int ph = getHeight();
            if (pw == 0 || ph == 0) return;

            // Capture absolute positions of all registered buttons
            Map<String, float[]> positions = new LinkedHashMap<>();
            for (Map.Entry<String, View> entry : buttonRegistry.entrySet()) {
                float[] absPos = getPositionInPanel(entry.getValue());
                positions.put(entry.getKey(), new float[]{absPos[0] / pw, absPos[1] / ph});
            }

            // Detach buttons from their current parents
            for (View btn : buttonRegistry.values()) {
                ViewGroup parent = (ViewGroup) btn.getParent();
                if (parent != null) parent.removeView(btn);
            }

            // Remove empty container views
            removeAllViews();

            // Re-add buttons with absolute positions
            for (Map.Entry<String, View> entry : buttonRegistry.entrySet()) {
                View v = entry.getValue();
                float[] pos = positions.get(entry.getKey());
                int w = v.getWidth() > 0 ? v.getWidth() : getButtonWidth(entry.getKey());
                int h = v.getHeight() > 0 ? v.getHeight() : getButtonHeight(entry.getKey());
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
                lp.leftMargin = (int) (pos[0] * pw);
                lp.topMargin = (int) (pos[1] * ph);
                addView(v, lp);
            }

            editMode = new LayoutEditMode(this, buttonRegistry, this::exitEditMode);
        });
    }

    private void exitEditMode(boolean saved) {
        editMode = null;
        rebuildLayout();
    }

    private void rebuildLayout() {
        removeAllViews();
        Context context = getContext();
        Map<String, float[]> positions = LayoutStore.load(context);
        if (positions != null) {
            buildCustomLayout(context, positions);
        } else {
            buildLayout(context);
        }
    }

    private float[] getPositionInPanel(View child) {
        float x = 0, y = 0;
        View v = child;
        while (v != this && v != null) {
            x += v.getLeft();
            y += v.getTop();
            if (v.getParent() instanceof View) {
                v = (View) v.getParent();
            } else {
                break;
            }
        }
        return new float[]{x, y};
    }

    private int getButtonWidth(String key) {
        switch (key) {
            case "menu": return dp(80);
            case "page": return dp(36);
            case "macro1": case "macro2": case "macro3": case "macro4": return dp(40);
            case "esc": case "ctl": case "shf": return dp(40);
            case "arrow_up": case "arrow_down": case "arrow_left": case "arrow_right": return dp(48);
            case "stt": return dp(52);
            case "s_tab": return dp(40);
            case "tab": return dp(48);
            case "enter": return dp(52);
            case "s_enter": return dp(40);
            default: return dp(40);
        }
    }

    private int getButtonHeight(String key) {
        switch (key) {
            case "menu": return dp(36);
            case "page": return dp(24);
            case "macro1": case "macro2": case "macro3": case "macro4": return dp(32);
            default: return getButtonWidth(key);
        }
    }

    // --- Button creation helpers (shared by buildLayout and buildCustomLayout) ---

    private Button createMenuButton(Context ctx) {
        Button btn = makeButton(ctx, dp(36), "\u2630", 16f, COLOR_MACRO, true);
        btn.setPadding(dp(24), 0, dp(24), 0);
        btn.setMinWidth(dp(80));
        btn.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) listener.onSettingsRequested();
        });
        buttonRegistry.put("menu", btn);
        return btn;
    }

    private Button createPageButton(Context ctx) {
        pageButton = makeButton(ctx, dp(24), "1/3", 8f, COLOR_MACRO, true);
        pageButton.setPadding(dp(8), 0, dp(8), 0);
        pageButton.setMinWidth(dp(36));
        pageButton.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            currentPage = (currentPage + 1) % MacroStore.PAGE_COUNT;
            updateMacroPage();
        });
        buttonRegistry.put("page", pageButton);
        return pageButton;
    }

    private Button createMacroButton(Context ctx, int index) {
        int actualIndex = currentPage * MacroStore.PAGE_SIZE + index;
        Button btn = makeButton(ctx, dp(32), macros[actualIndex][0], 10f, COLOR_MACRO, true);
        btn.setPadding(dp(12), 0, dp(12), 0);
        btn.setMinWidth(dp(40));
        btn.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) {
                int ai = currentPage * MacroStore.PAGE_SIZE + index;
                MacroExecutor.execute(macros[ai][1], listener::onSendToTerminal, v.getHandler());
            }
        });
        btn.setOnLongClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            int ai = currentPage * MacroStore.PAGE_SIZE + index;
            MacroEditDialog.show(ctx, macros[ai][0], macros[ai][1],
                    (label, cmd) -> {
                        int ai2 = currentPage * MacroStore.PAGE_SIZE + index;
                        macros[ai2][0] = label;
                        macros[ai2][1] = cmd;
                        macroButtons[index].setText(label);
                        MacroStore.save(ctx, macros);
                    });
            return true;
        });
        macroButtons[index] = btn;
        buttonRegistry.put("macro" + (index + 1), btn);
        return btn;
    }

    private Button createEscButton(Context ctx) {
        Button btn = makeButton(ctx, dp(40), "ESC", 9f, COLOR_MODIFIER, false);
        btn.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) listener.onSendToTerminal("\033");
        });
        buttonRegistry.put("esc", btn);
        return btn;
    }

    private Button createCtrlButton(Context ctx) {
        ctrlButton = makeButton(ctx, dp(40), "CTL", 9f, COLOR_MODIFIER, false);
        ctrlButton.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            ctrlActive = !ctrlActive;
            updateButtonColor(ctrlButton, ctrlActive);
        });
        buttonRegistry.put("ctl", ctrlButton);
        return ctrlButton;
    }

    private Button createShiftButton(Context ctx) {
        shiftButton = makeButton(ctx, dp(40), "SHF", 9f, COLOR_MODIFIER, false);
        shiftButton.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            shiftActive = !shiftActive;
            updateButtonColor(shiftButton, shiftActive);
        });
        buttonRegistry.put("shf", shiftButton);
        return shiftButton;
    }

    private Button createSttButton(Context ctx) {
        Button btn = makeButton(ctx, dp(52), "\uD83C\uDFA4", 18f, COLOR_DPAD, false);
        btn.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) listener.onVoiceToggle();
        });
        buttonRegistry.put("stt", btn);
        return btn;
    }

    private Button createArrowButton(Context ctx, String name, String label, String escSeq) {
        Button btn = makeButton(ctx, dp(48), label, 14f, COLOR_DPAD, false);
        setupArrowRepeat(btn, escSeq);
        buttonRegistry.put(name, btn);
        return btn;
    }

    private Button createSTabButton(Context ctx) {
        Button btn = makeButton(ctx, dp(40), "S-TAB", 8f, COLOR_MODIFIER, false);
        btn.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) listener.onSendToTerminal("\033[Z");
        });
        buttonRegistry.put("s_tab", btn);
        return btn;
    }

    private Button createTabButton(Context ctx) {
        Button btn = makeButton(ctx, dp(48), "TAB", 10f, COLOR_PRIMARY, false);
        btn.setOnClickListener(v -> {
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
        buttonRegistry.put("tab", btn);
        return btn;
    }

    private Button createEnterButton(Context ctx) {
        Button btn = makeButton(ctx, dp(52), "\u21B5", 18f, COLOR_PRIMARY, false);
        btn.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) listener.onSendToTerminal("\r");
        });
        buttonRegistry.put("enter", btn);
        return btn;
    }

    private Button createSEnterButton(Context ctx) {
        Button btn = makeButton(ctx, dp(40), "S-\u21B5", 8f, COLOR_MODIFIER, false);
        btn.setOnClickListener(v -> {
            if (SettingsDialog.isHapticEnabled(getContext())) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) listener.onSendToTerminal("\n");
        });
        buttonRegistry.put("s_enter", btn);
        return btn;
    }

    // --- Zone builders (layout-only, delegate button creation to helpers) ---

    private View buildMacroZone(Context context) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);

        int spacing = dp(4);

        Button pgBtn = createPageButton(context);
        LinearLayout.LayoutParams pageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(24));
        pageLp.gravity = Gravity.CENTER_HORIZONTAL;
        pageLp.bottomMargin = spacing;
        col.addView(pgBtn, pageLp);

        for (int i = 0; i < 4; i++) {
            Button btn = createMacroButton(context, i);
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
        Button stt = createSttButton(context);
        int sttId = View.generateViewId();
        stt.setId(sttId);
        RelativeLayout.LayoutParams sttLp = new RelativeLayout.LayoutParams(sttSize, sttSize);
        sttLp.addRule(RelativeLayout.CENTER_IN_PARENT);
        dpad.addView(stt, sttLp);

        // UP — above STT
        Button up = createArrowButton(context, "arrow_up", "\u25B2", "\033[A");
        RelativeLayout.LayoutParams upLp = new RelativeLayout.LayoutParams(arrowSize, arrowSize);
        upLp.addRule(RelativeLayout.ABOVE, sttId);
        upLp.addRule(RelativeLayout.CENTER_HORIZONTAL);
        upLp.bottomMargin = gap;
        dpad.addView(up, upLp);

        // DOWN — below STT
        Button down = createArrowButton(context, "arrow_down", "\u25BC", "\033[B");
        RelativeLayout.LayoutParams downLp = new RelativeLayout.LayoutParams(arrowSize, arrowSize);
        downLp.addRule(RelativeLayout.BELOW, sttId);
        downLp.addRule(RelativeLayout.CENTER_HORIZONTAL);
        downLp.topMargin = gap;
        dpad.addView(down, downLp);

        // LEFT — left of STT
        Button left = createArrowButton(context, "arrow_left", "\u25C0", "\033[D");
        RelativeLayout.LayoutParams leftLp = new RelativeLayout.LayoutParams(arrowSize, arrowSize);
        leftLp.addRule(RelativeLayout.LEFT_OF, sttId);
        leftLp.addRule(RelativeLayout.CENTER_VERTICAL);
        leftLp.rightMargin = gap;
        dpad.addView(left, leftLp);

        // RIGHT — right of STT
        Button right = createArrowButton(context, "arrow_right", "\u25B6", "\033[C");
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

    private View buildModifierZone(Context context) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);

        int btnSize = dp(40);
        int spacing = dp(4);

        Button esc = createEscButton(context);
        LinearLayout.LayoutParams escLp = new LinearLayout.LayoutParams(btnSize, btnSize);
        escLp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(esc, escLp);

        Button ctl = createCtrlButton(context);
        LinearLayout.LayoutParams ctlLp = new LinearLayout.LayoutParams(btnSize, btnSize);
        ctlLp.gravity = Gravity.CENTER_HORIZONTAL;
        ctlLp.topMargin = spacing;
        col.addView(ctl, ctlLp);

        Button shf = createShiftButton(context);
        LinearLayout.LayoutParams shfLp = new LinearLayout.LayoutParams(btnSize, btnSize);
        shfLp.gravity = Gravity.CENTER_HORIZONTAL;
        shfLp.topMargin = spacing;
        col.addView(shf, shfLp);

        return col;
    }

    private View buildPrimaryZone(Context context) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);

        int spacing = dp(6);

        // S-TAB (Shift+Tab / backtab) — 40dp
        Button sTab = createSTabButton(context);
        int sTabSize = dp(40);
        LinearLayout.LayoutParams sTabLp = new LinearLayout.LayoutParams(sTabSize, sTabSize);
        sTabLp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(sTab, sTabLp);

        // TAB — 48dp, matches arrow size
        Button tab = createTabButton(context);
        int tabSize = dp(48);
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(tabSize, tabSize);
        tabLp.gravity = Gravity.CENTER_HORIZONTAL;
        tabLp.topMargin = spacing;
        col.addView(tab, tabLp);

        // Enter — 52dp, largest button in the panel
        Button enter = createEnterButton(context);
        int enterSize = dp(52);
        LinearLayout.LayoutParams enterLp = new LinearLayout.LayoutParams(enterSize, enterSize);
        enterLp.gravity = Gravity.CENTER_HORIZONTAL;
        enterLp.topMargin = spacing;
        col.addView(enter, enterLp);

        // S-Enter (Shift+Enter / newline without submit) — 40dp
        Button sEnter = createSEnterButton(context);
        int sEnterSize = dp(40);
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
