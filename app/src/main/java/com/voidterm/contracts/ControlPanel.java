package com.voidterm.contracts;

/**
 * Common modifier and macro state API shared by all control panels
 * (GameBoyControlPanel, CompactPanel, CompactToolbar).
 *
 * Used by PanelController to sync state across panel transitions
 * and by VoidTermTerminalViewClient to read modifier key state.
 */
public interface ControlPanel {
    boolean isCtrlActive();
    void setCtrlActive(boolean active);
    void resetCtrl();

    boolean isShiftActive();
    void setShiftActive(boolean active);
    void resetShift();

    int getCurrentMacroPage();
    void setCurrentMacroPage(int page);
}
