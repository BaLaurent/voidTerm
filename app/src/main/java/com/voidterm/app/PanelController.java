package com.voidterm.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.voidterm.contracts.ControlPanel;
import com.voidterm.contracts.ControlPanelListener;

/**
 * Manages the three control panels (GameBoy, Compact, CompactToolbar).
 * Extracted from TermuxActivity to consolidate panel creation, theme
 * application, and visibility management in a single class.
 *
 * Tracks the currently active panel for O(1) modifier key consumption
 * by VoidTermTerminalViewClient.
 */
public class PanelController {

    private final LinearLayout rootLayout;
    private final Context context;
    private GameBoyControlPanel controlPanel;
    private CompactPanel compactPanel;
    private CompactToolbar compactToolbar;

    /** The panel currently visible, or null if all hidden. */
    private ControlPanel activePanel;

    public PanelController(Context context, LinearLayout rootLayout,
                           ControlPanelListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        this.context = context;
        this.rootLayout = rootLayout;
        createPanels(listener);
    }

    private void createPanels(ControlPanelListener listener) {
        // GameBoy control panel (weight=2, ~40%)
        controlPanel = new GameBoyControlPanel(context);
        controlPanel.setControlPanelListener(listener);
        controlPanel.setBackgroundColor(InterfaceTheme.current(context).background);
        rootLayout.addView(controlPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f));

        // Compact panel (hidden by default, 170dp, 4 rows of 6 buttons)
        compactPanel = new CompactPanel(context);
        compactPanel.setControlPanelListener(listener);
        compactPanel.setVisibility(View.GONE);
        rootLayout.addView(compactPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                PanelUtils.dp(context, 170)));

        // Compact toolbar (hidden by default, 48dp, shown when keyboard visible)
        compactToolbar = new CompactToolbar(context);
        compactToolbar.setControlPanelListener(listener);
        compactToolbar.setVisibility(View.GONE);
        rootLayout.addView(compactToolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                PanelUtils.dp(context, 48)));
    }

    /**
     * Recreate all panels with the current theme.
     * Preserves layout positions and params. Caller must call
     * updateVisibility() afterward to restore correct visibility.
     *
     * Assumes panels occupy consecutive indices in rootLayout (guaranteed by createPanels).
     */
    public void applyTheme(ControlPanelListener listener) {
        // Save layout params (panels are always consecutive: control, compact, toolbar)
        int insertBase = rootLayout.indexOfChild(controlPanel);
        LinearLayout.LayoutParams controlPanelLp =
                (LinearLayout.LayoutParams) controlPanel.getLayoutParams();
        LinearLayout.LayoutParams compactPanelLp =
                (LinearLayout.LayoutParams) compactPanel.getLayoutParams();
        LinearLayout.LayoutParams compactToolbarLp =
                (LinearLayout.LayoutParams) compactToolbar.getLayoutParams();

        // Remove old panels
        rootLayout.removeView(controlPanel);
        rootLayout.removeView(compactPanel);
        rootLayout.removeView(compactToolbar);

        // Recreate (constructors read the current theme)
        controlPanel = new GameBoyControlPanel(context);
        controlPanel.setControlPanelListener(listener);
        controlPanel.setBackgroundColor(InterfaceTheme.current(context).background);
        controlPanel.setVisibility(View.GONE);

        compactPanel = new CompactPanel(context);
        compactPanel.setControlPanelListener(listener);
        compactPanel.setVisibility(View.GONE);

        compactToolbar = new CompactToolbar(context);
        compactToolbar.setControlPanelListener(listener);
        compactToolbar.setVisibility(View.GONE);

        activePanel = null;

        // Re-insert at original base index (same order as createPanels)
        rootLayout.addView(controlPanel, insertBase, controlPanelLp);
        rootLayout.addView(compactPanel, insertBase + 1, compactPanelLp);
        rootLayout.addView(compactToolbar, insertBase + 2, compactToolbarLp);
    }

    /**
     * Set correct panel visibility based on panel mode, toolbar preference,
     * and keyboard state. Syncs modifier keys and macro page across panels.
     */
    public void updateVisibility(boolean keyboardVisible) {
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE);
        String panelMode = SettingsDialog.migratePanelMode(prefs);

        // Collect modifier/macro state from the active panel
        boolean ctrl = false, shift = false;
        int macroPage = 0;
        if (activePanel != null) {
            ctrl = activePanel.isCtrlActive();
            shift = activePanel.isShiftActive();
            macroPage = activePanel.getCurrentMacroPage();
        }

        // Hide all panels first
        controlPanel.setVisibility(View.GONE);
        compactPanel.setVisibility(View.GONE);
        compactToolbar.setVisibility(View.GONE);
        activePanel = null;

        if (SettingsDialog.PANEL_FULLSCREEN.equals(panelMode)) {
            showPanel(compactToolbar, ctrl, shift, macroPage);
            return;
        }

        boolean toolbarEnabled = prefs.getBoolean(SettingsDialog.KEY_COMPACT_TOOLBAR, true);

        if (SettingsDialog.PANEL_COMPACT.equals(panelMode)) {
            if (keyboardVisible && toolbarEnabled) {
                showPanel(compactToolbar, ctrl, shift, macroPage);
            } else {
                showPanel(compactPanel, ctrl, shift, macroPage);
            }
            return;
        }

        // Default: PANEL_GAMEBOY
        if (keyboardVisible && toolbarEnabled) {
            showPanel(compactToolbar, ctrl, shift, macroPage);
        } else {
            showPanel(controlPanel, ctrl, shift, macroPage);
        }
    }

    /**
     * Make a panel visible and restore modifier/macro state.
     * Updates the activePanel reference for O(1) modifier consumption.
     */
    private void showPanel(View panel, boolean ctrl, boolean shift, int macroPage) {
        panel.setVisibility(View.VISIBLE);
        ControlPanel cp = (ControlPanel) panel;
        cp.setCtrlActive(ctrl);
        cp.setShiftActive(shift);
        cp.setCurrentMacroPage(macroPage);
        activePanel = cp;
    }

    /**
     * Read and reset the Ctrl modifier from the active panel.
     * Returns true if Ctrl was active (and is now reset).
     */
    public boolean consumeCtrl() {
        if (activePanel != null && activePanel.isCtrlActive()) {
            activePanel.resetCtrl();
            return true;
        }
        return false;
    }

    /**
     * Read and reset the Shift modifier from the active panel.
     * Returns true if Shift was active (and is now reset).
     */
    public boolean consumeShift() {
        if (activePanel != null && activePanel.isShiftActive()) {
            activePanel.resetShift();
            return true;
        }
        return false;
    }

    /**
     * Hide all panels (used during bootstrap install progress).
     */
    public void hideAll() {
        controlPanel.setVisibility(View.GONE);
        compactPanel.setVisibility(View.GONE);
        compactToolbar.setVisibility(View.GONE);
        activePanel = null;
    }

    public GameBoyControlPanel getControlPanel() {
        return controlPanel;
    }

    public CompactPanel getCompactPanel() {
        return compactPanel;
    }

    public CompactToolbar getCompactToolbar() {
        return compactToolbar;
    }
}
