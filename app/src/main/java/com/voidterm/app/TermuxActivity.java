package com.voidterm.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;
import com.voidterm.contracts.VoiceInputCallback;
import com.voidterm.contracts.VoiceState;
import com.voidterm.input.QuestInputHandler;
import com.voidterm.voice.TranscriptionOverlay;
import com.voidterm.voice.VoiceInputManager;

/**
 * Main VoidTerm activity. Hosts a real terminal emulator (TerminalView + TerminalSession)
 * with voice input and Quest controller support.
 */
public class TermuxActivity extends Activity implements VoiceInputCallback,
        GameBoyControlPanel.ControlPanelListener {

    private static final String TAG = "TermuxActivity";

    private TerminalView terminalView;
    private TerminalSession terminalSession;
    private TermuxTerminalSessionClient sessionClient;
    private VoidTermTerminalViewClient viewClient;

    private DiagnosticLog diagnosticLog;
    private VoiceInputManager voiceInputManager;
    private QuestInputHandler questInputHandler;
    private TranscriptionOverlay transcriptionOverlay;
    private ExtraKeysConfig extraKeysConfig;
    private GameBoyControlPanel controlPanel;
    private CompactToolbar compactToolbar;
    private boolean keyboardVisible = false;

    private TermuxBootstrapInstaller bootstrapInstaller;
    private LinearLayout rootLayout;
    private FrameLayout screenFrame;
    private LinearLayout installProgressView;
    private ProgressBar installProgressBar;
    private TextView installStatusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Global crash handler
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "UNCAUGHT EXCEPTION", throwable);
            if (diagnosticLog != null) {
                diagnosticLog.error("CRASH", "Uncaught exception on thread " + thread.getName(), throwable);
            }
        });

        // DiagnosticLog (hidden by default, available for debugging)
        diagnosticLog = new DiagnosticLog(this);

        // Root layout — vertical split: screen (60%) + controls (40%)
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.setBackgroundColor(Color.BLACK);

        // Create session client and view client
        sessionClient = new TermuxTerminalSessionClient(this, diagnosticLog);
        viewClient = new VoidTermTerminalViewClient(this);

        // Screen frame (weight=3, ~60%) — holds terminal + overlay
        screenFrame = new FrameLayout(this);
        screenFrame.setBackgroundColor(Color.BLACK);

        // Create TerminalView (the real terminal renderer)
        terminalView = new TerminalView(this, null);
        terminalView.setTerminalViewClient(viewClient);
        terminalView.setTextSize(20); // Quest-optimized default (vs Termux 14)
        terminalView.setKeepScreenOn(true);
        terminalView.setFocusable(true);
        terminalView.setFocusableInTouchMode(true);
        terminalView.requestFocus();
        registerForContextMenu(terminalView);
        screenFrame.addView(terminalView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // TranscriptionOverlay on top of terminal
        transcriptionOverlay = new TranscriptionOverlay(this);
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        screenFrame.addView(transcriptionOverlay, overlayParams);

        rootLayout.addView(screenFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f));

        // GameBoy control panel (weight=2, ~40%)
        controlPanel = new GameBoyControlPanel(this);
        controlPanel.setControlPanelListener(this);
        controlPanel.setBackgroundColor(InterfaceTheme.current(this).background);
        rootLayout.addView(controlPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f));

        // Compact toolbar (hidden by default, shown when keyboard is visible)
        compactToolbar = new CompactToolbar(this);
        compactToolbar.setControlPanelListener(this);
        compactToolbar.setVisibility(View.GONE);
        rootLayout.addView(compactToolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48,
                        getResources().getDisplayMetrics())));

        setContentView(rootLayout);

        // Keyboard visibility detection via layout height change
        rootLayout.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            Rect r = new Rect();
            rootLayout.getWindowVisibleDisplayFrame(r);
            int heightDiff = rootLayout.getRootView().getHeight() - r.height();
            int threshold = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 150, getResources().getDisplayMetrics());
            boolean isKeyboardNow = heightDiff > threshold;
            if (isKeyboardNow != keyboardVisible) {
                keyboardVisible = isKeyboardNow;
                onKeyboardVisibilityChanged(isKeyboardNow);
            }
        });

        // Check if bootstrap is installed, install if needed
        bootstrapInstaller = new TermuxBootstrapInstaller(getFilesDir());

        if (bootstrapInstaller.isInstalled()) {
            onBootstrapReady();
        } else {
            showInstallProgress();
            bootstrapInstaller.install(new TermuxBootstrapInstaller.BootstrapCallback() {
                @Override
                public void onProgressUpdate(String message, int percent) {
                    updateInstallProgress(message, percent);
                }

                @Override
                public void onInstallComplete() {
                    hideInstallProgress();
                    onBootstrapReady();
                }

                @Override
                public void onInstallFailed(String error) {
                    showInstallError(error);
                }
            });
        }
    }

    private void startTerminalSession() {
        try {
            String filesDir = getFilesDir().getAbsolutePath();
            String prefix = filesDir + "/usr";
            String home = filesDir + "/home";

            // Select shell: prefer bash > sh fallback
            // NOTE: skip "login" — it's a script with shebang #!/data/data/com.termux/...
            // which points to Termux's package, not ours. Using bash (ELF) directly.
            String shell;
            if (new java.io.File(prefix + "/bin/bash").exists()) {
                shell = prefix + "/bin/bash";
            } else {
                shell = "/system/bin/sh";
            }

            String[] env = buildEnvironment(prefix, home);

            // Use --rcfile to avoid bash sourcing its compiled-in /data/data/com.termux/.../profile
            // Our ~/.bashrc sources the correct $PREFIX/etc/profile instead.
            String[] args;
            if (shell.endsWith("/bash")) {
                args = new String[]{"bash", "--rcfile", home + "/.bashrc"};
            } else {
                args = new String[]{shell};
            }

            terminalSession = new TerminalSession(
                    shell,
                    home,
                    args,
                    env,
                    null,
                    sessionClient
            );

            terminalView.attachSession(terminalSession);
            sessionClient.setSession(terminalSession);

            Log.i(TAG, "Terminal session started: shell=" + shell + " home=" + home);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start terminal session", e);
            if (diagnosticLog != null) {
                diagnosticLog.error(TAG, "Failed to start terminal session", e);
            }
        }
    }

    private String[] buildEnvironment(String prefix, String home) {
        String libDir = prefix + "/lib";

        return new String[]{
                "TERM=xterm-256color",
                "COLORTERM=truecolor",
                "HOME=" + home,
                "PREFIX=" + prefix,
                "TERMUX_PREFIX=" + prefix,
                "TERMUX__PREFIX=" + prefix,
                "LANG=en_US.UTF-8",
                "PATH=" + prefix + "/bin:" + prefix + "/bin/applets",
                "LD_LIBRARY_PATH=" + libDir,
                "TMPDIR=" + prefix + "/tmp",
                "SHELL=" + prefix + "/bin/bash",
                "TERMINFO=" + prefix + "/share/terminfo",
        };
    }

    private void initVoiceInput() {
        try {
            voiceInputManager = new VoiceInputManager(this, transcriptionOverlay, this);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create VoiceInputManager", e);
        }
    }

    private void initQuestInput() {
        try {
            questInputHandler = new QuestInputHandler(voiceInputManager);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create QuestInputHandler", e);
        }
    }

    private void initExtraKeys() {
        try {
            extraKeysConfig = new ExtraKeysConfig(voiceInputManager);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create ExtraKeysConfig", e);
        }
    }

    private void onBootstrapReady() {
        if (bootstrapInstaller.needsRepatch()) {
            Log.i(TAG, "Patch version outdated, re-patching...");
            showInstallProgress();
            bootstrapInstaller.repatch(new TermuxBootstrapInstaller.BootstrapCallback() {
                @Override
                public void onProgressUpdate(String message, int percent) {
                    updateInstallProgress(message, percent);
                }

                @Override
                public void onInstallComplete() {
                    hideInstallProgress();
                    startTerminalAndInit();
                }

                @Override
                public void onInstallFailed(String error) {
                    showInstallError(error);
                }
            });
        } else {
            startTerminalAndInit();
        }
    }

    private void startTerminalAndInit() {
        startTerminalSession();
        initVoiceInput();
        initQuestInput();
        initExtraKeys();
        Log.i(TAG, "VoidTerm initialized with real terminal");
    }

    private void showInstallProgress() {
        installProgressView = new LinearLayout(this);
        installProgressView.setOrientation(LinearLayout.VERTICAL);
        installProgressView.setGravity(Gravity.CENTER);
        installProgressView.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("Installing Termux environment...");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(Typeface.MONOSPACE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(32, 0, 32, 24);
        installProgressView.addView(title);

        installProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        installProgressBar.setMax(100);
        installProgressBar.setProgress(0);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(600, ViewGroup.LayoutParams.WRAP_CONTENT);
        barParams.gravity = Gravity.CENTER;
        installProgressView.addView(installProgressBar, barParams);

        installStatusText = new TextView(this);
        installStatusText.setText("Starting...");
        installStatusText.setTextColor(0xFFAAAAAA);
        installStatusText.setTextSize(14);
        installStatusText.setTypeface(Typeface.MONOSPACE);
        installStatusText.setGravity(Gravity.CENTER);
        installStatusText.setPadding(32, 16, 32, 0);
        installProgressView.addView(installStatusText);

        rootLayout.addView(installProgressView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Hide screen and controls while installing
        screenFrame.setVisibility(android.view.View.GONE);
        controlPanel.setVisibility(android.view.View.GONE);
    }

    private void updateInstallProgress(String message, int percent) {
        if (installProgressBar != null) installProgressBar.setProgress(percent);
        if (installStatusText != null) installStatusText.setText(message);
    }

    private void hideInstallProgress() {
        if (installProgressView != null) {
            rootLayout.removeView(installProgressView);
            installProgressView = null;
        }
        screenFrame.setVisibility(android.view.View.VISIBLE);
        controlPanel.setVisibility(android.view.View.VISIBLE);
        terminalView.requestFocus();
    }

    private void showInstallError(String error) {
        if (installStatusText != null) {
            installStatusText.setText("Error: " + error + "\n\nTap to retry");
            installStatusText.setTextColor(0xFFFF4444);
        }
        if (installProgressView != null) {
            installProgressView.setOnClickListener(v -> {
                if (installStatusText != null) {
                    installStatusText.setTextColor(0xFFAAAAAA);
                }
                if (installProgressBar != null) installProgressBar.setProgress(0);
                bootstrapInstaller.install(new TermuxBootstrapInstaller.BootstrapCallback() {
                    @Override
                    public void onProgressUpdate(String message, int percent) {
                        updateInstallProgress(message, percent);
                    }

                    @Override
                    public void onInstallComplete() {
                        hideInstallProgress();
                        onBootstrapReady();
                    }

                    @Override
                    public void onInstallFailed(String err) {
                        showInstallError(err);
                    }
                });
            });
        }
    }

    /**
     * Intercept key events for Quest controller button mapping.
     * QuestInputHandler gets first chance to consume PTT events.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (questInputHandler != null && questInputHandler.onKeyDown(keyCode, event)) {
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && handleCustomBackKey()) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean handleCustomBackKey() {
        SharedPreferences prefs = getSharedPreferences(SettingsDialog.PREFS_NAME, MODE_PRIVATE);
        String behavior = prefs.getString(SettingsDialog.KEY_BACK_BEHAVIOR, SettingsDialog.BACK_ESCAPE);

        if (SettingsDialog.BACK_ESCAPE.equals(behavior)) {
            return false; // let TerminalView handle it
        }

        if (SettingsDialog.BACK_TOGGLE_KEYBOARD.equals(behavior)) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0);
            }
            return true;
        }

        if (SettingsDialog.BACK_MACRO.equals(behavior)) {
            String macro = prefs.getString(SettingsDialog.KEY_BACK_MACRO, "");
            if (!macro.isEmpty() && terminalSession != null) {
                MacroExecutor.execute(macro, terminalSession::write,
                        terminalView != null ? terminalView.getHandler() : null);
            }
            return true;
        }

        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (questInputHandler != null && questInputHandler.onKeyUp(keyCode, event)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // --- VoiceInputCallback implementation ---

    @Override
    public void onVoiceTextReady(String text) {
        Log.i(TAG, "Voice text ready: \"" + text + "\"");
        if (sessionClient != null) {
            sessionClient.injectVoiceText(text);
        }
    }

    @Override
    public void onVoiceStateChanged(VoiceState newState) {
        Log.d(TAG, "Voice state -> " + newState);
    }

    // --- ControlPanelListener implementation ---

    @Override
    public void onSendToTerminal(String text) {
        if (terminalSession != null) {
            terminalSession.write(text);
        }
    }

    @Override
    public void onVoiceToggle() {
        if (extraKeysConfig != null) {
            extraKeysConfig.onExtraKeyPressed(ExtraKeysConfig.MIC_BUTTON_KEY);
        }
    }

    @Override
    public void onSettingsRequested() {
        new SettingsDialog(this, this::reloadCurrentModel, () -> controlPanel.enterEditMode()).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SettingsDialog.REQUEST_MODEL_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                onModelFileSelected(uri);
            }
        }
    }

    private void onModelFileSelected(Uri uri) {
        String filename = getFileNameFromUri(uri);
        if (filename == null) {
            Log.e(TAG, "Could not resolve filename from URI");
            return;
        }

        File modelsDir = new File(getFilesDir(), "models");
        if (!modelsDir.exists()) {
            modelsDir.mkdirs();
        }
        File destFile = new File(modelsDir, filename);

        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(destFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
            Log.i(TAG, "Model file copied: " + destFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy model file", e);
            return;
        }

        SharedPreferences prefs = getSharedPreferences(SettingsDialog.PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putString(SettingsDialog.KEY_MODEL_NAME, filename).apply();

        if (voiceInputManager != null) {
            voiceInputManager.reloadModel(this, filename);
        }
    }

    private void reloadCurrentModel() {
        if (voiceInputManager != null) {
            SharedPreferences prefs = getSharedPreferences(SettingsDialog.PREFS_NAME, MODE_PRIVATE);
            String modelName = prefs.getString(SettingsDialog.KEY_MODEL_NAME, SettingsDialog.DEFAULT_MODEL);
            voiceInputManager.reloadModel(this, modelName);
        }
    }

    private String getFileNameFromUri(Uri uri) {
        String name = null;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    name = cursor.getString(index);
                }
            }
        }
        return name;
    }

    /**
     * Paste text from clipboard into the terminal session.
     * Called by TermuxTerminalSessionClient.onPasteTextFromClipboard().
     */
    public void pasteFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        ClipData clipData = clipboard.getPrimaryClip();
        if (clipData == null || clipData.getItemCount() == 0) return;
        CharSequence text = clipData.getItemAt(0).coerceToText(this);
        if (text != null && terminalSession != null) {
            terminalSession.write(text.toString());
        }
    }

    /**
     * Get the terminal view (used by view client and session client).
     */
    public TerminalView getTerminalView() {
        return terminalView;
    }

    /**
     * Get the current terminal session.
     */
    public TerminalSession getCurrentSession() {
        return terminalSession;
    }

    /**
     * Get the control panel (used by view client for modifier key state).
     */
    public GameBoyControlPanel getControlPanel() {
        return controlPanel;
    }

    public boolean isKeyboardVisible() {
        return keyboardVisible;
    }

    public CompactToolbar getCompactToolbar() {
        return compactToolbar;
    }

    /**
     * Recreate both control panels with the current theme.
     * Called from SettingsDialog after theme selection changes.
     */
    public void applyTheme() {
        // Save state
        boolean controlPanelVisible = controlPanel.getVisibility() == View.VISIBLE;
        int controlPanelIndex = rootLayout.indexOfChild(controlPanel);
        LinearLayout.LayoutParams controlPanelLp =
                (LinearLayout.LayoutParams) controlPanel.getLayoutParams();

        int compactToolbarIndex = rootLayout.indexOfChild(compactToolbar);
        LinearLayout.LayoutParams compactToolbarLp =
                (LinearLayout.LayoutParams) compactToolbar.getLayoutParams();
        boolean compactToolbarVisible = compactToolbar.getVisibility() == View.VISIBLE;

        // Remove old
        rootLayout.removeView(controlPanel);
        rootLayout.removeView(compactToolbar);

        // Recreate (constructors read the current theme)
        controlPanel = new GameBoyControlPanel(this);
        controlPanel.setControlPanelListener(this);
        controlPanel.setBackgroundColor(InterfaceTheme.current(this).background);
        controlPanel.setVisibility(controlPanelVisible ? View.VISIBLE : View.GONE);

        compactToolbar = new CompactToolbar(this);
        compactToolbar.setControlPanelListener(this);
        compactToolbar.setVisibility(compactToolbarVisible ? View.VISIBLE : View.GONE);

        // Re-insert at same positions
        int insertIndex = Math.min(controlPanelIndex, compactToolbarIndex);
        if (controlPanelIndex < compactToolbarIndex) {
            rootLayout.addView(controlPanel, insertIndex, controlPanelLp);
            rootLayout.addView(compactToolbar, insertIndex + 1, compactToolbarLp);
        } else {
            rootLayout.addView(compactToolbar, insertIndex, compactToolbarLp);
            rootLayout.addView(controlPanel, insertIndex + 1, controlPanelLp);
        }
    }

    private void onKeyboardVisibilityChanged(boolean visible) {
        SharedPreferences prefs = getSharedPreferences(SettingsDialog.PREFS_NAME, MODE_PRIVATE);
        boolean toolbarEnabled = prefs.getBoolean(SettingsDialog.KEY_COMPACT_TOOLBAR, true);
        if (!toolbarEnabled) return;

        if (visible) {
            compactToolbar.setCtrlActive(controlPanel.isCtrlActive());
            compactToolbar.setShiftActive(controlPanel.isShiftActive());
            controlPanel.setVisibility(View.GONE);
            compactToolbar.setVisibility(View.VISIBLE);
        } else {
            compactToolbar.setVisibility(View.GONE);
            controlPanel.setVisibility(View.VISIBLE);
        }
    }

    // --- Context menu ("More" button) ---

    private static final int CONTEXT_MENU_SELECT_URL = 0;
    private static final int CONTEXT_MENU_SHARE_TEXT = 1;
    private static final int CONTEXT_MENU_RESET_TERMINAL = 2;
    private static final int CONTEXT_MENU_TOGGLE_KEYBOARD = 3;
    private static final int CONTEXT_MENU_PASTE = 4;
    private static final int CONTEXT_MENU_STYLE = 5;

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        String selectedText = terminalView.getStoredSelectedText();
        boolean hasSelection = !TextUtils.isEmpty(selectedText);

        menu.add(0, CONTEXT_MENU_PASTE, 0, "Paste");
        if (hasSelection) {
            menu.add(0, CONTEXT_MENU_SHARE_TEXT, 0, "Share selected text");
        }
        menu.add(0, CONTEXT_MENU_SELECT_URL, 0, "Select URL");
        menu.add(0, CONTEXT_MENU_STYLE, 0, "Style");
        menu.add(0, CONTEXT_MENU_RESET_TERMINAL, 0, "Reset terminal");
        menu.add(0, CONTEXT_MENU_TOGGLE_KEYBOARD, 0, "Toggle keyboard");
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case CONTEXT_MENU_PASTE:
                pasteFromClipboard();
                return true;
            case CONTEXT_MENU_SHARE_TEXT: {
                String text = terminalView.getStoredSelectedText();
                terminalView.unsetStoredSelectedText();
                if (!TextUtils.isEmpty(text)) {
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("text/plain");
                    shareIntent.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(shareIntent, "Share text"));
                }
                return true;
            }
            case CONTEXT_MENU_SELECT_URL:
                selectUrlFromTerminal();
                return true;
            case CONTEXT_MENU_RESET_TERMINAL:
                if (terminalSession != null) {
                    terminalSession.reset();
                    terminalView.invalidate();
                }
                return true;
            case CONTEXT_MENU_STYLE:
                new TerminalStyleDialog(this, terminalView).show();
                return true;
            case CONTEXT_MENU_TOGGLE_KEYBOARD:
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.toggleSoftInput(android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT, 0);
                }
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    private void selectUrlFromTerminal() {
        if (terminalView.mEmulator == null) return;
        String text = terminalView.mEmulator.getScreen().getTranscriptText();
        // Find last URL-like pattern in the terminal buffer
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+")
                .matcher(text);
        String lastUrl = null;
        while (matcher.find()) {
            lastUrl = matcher.group();
        }
        if (lastUrl != null) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("URL", lastUrl));
                Log.i(TAG, "URL copied to clipboard: " + lastUrl);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (voiceInputManager != null) {
            voiceInputManager.destroy();
            voiceInputManager = null;
        }
        if (terminalSession != null) {
            terminalSession.finishIfRunning();
        }
        Log.i(TAG, "VoidTerm destroyed");
    }
}
