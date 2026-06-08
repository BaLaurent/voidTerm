package com.voidterm.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.media.AudioManager;
import android.view.inputmethod.InputMethodManager;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.drawerlayout.widget.DrawerLayout;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;
import com.voidterm.contracts.ControlPanelListener;
import com.voidterm.contracts.VoiceInputCallback;
import com.voidterm.contracts.VoiceState;
import com.voidterm.input.GestureTiming;
import com.voidterm.input.HandlerScheduler;
import com.voidterm.input.KeyGestureDetector;
import com.voidterm.input.QuestInputHandler;
import com.voidterm.voice.TranscriptionOverlay;
import com.voidterm.voice.VoiceInputManager;

/**
 * Main VoidTerm activity. Hosts a real terminal emulator (TerminalView + TerminalSession)
 * with voice input and Quest controller support.
 */
public class TermuxActivity extends Activity implements VoiceInputCallback,
        ControlPanelListener {

    private static final String TAG = "TermuxActivity";

    private TerminalView terminalView;
    private SessionManager sessionManager;
    private TerminalService terminalService;
    private boolean serviceBound;
    private TermuxTerminalSessionClient sessionClient;
    private VoidTermTerminalViewClient viewClient;

    private DiagnosticLog diagnosticLog;
    private VoiceInputManager voiceInputManager;
    private QuestInputHandler questInputHandler;
    private KeyGestureDetector gestureDetector;
    private TranscriptionOverlay transcriptionOverlay;
    private ExtraKeysConfig extraKeysConfig;
    private PanelController panelController;
    private boolean keyboardVisible = false;
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;
    private final Rect visibleDisplayRect = new Rect();

    private InterfaceTheme lastAppliedTheme;

    private DrawerLayout drawerLayout;
    private LinearLayout drawerPanel;
    private SessionListAdapter sessionListAdapter;

    private TermuxBootstrapInstaller bootstrapInstaller;
    private LinearLayout rootLayout;
    private FrameLayout screenFrame;
    private LinearLayout installProgressView;
    private ProgressBar installProgressBar;
    private TextView installStatusText;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            TerminalService.LocalBinder binder = (TerminalService.LocalBinder) service;
            terminalService = binder.getService();
            serviceBound = true;
            sessionManager = terminalService.getSessionManager();
            setupSessionManagerListener();

            if (sessionManager.getSessionCount() > 0) {
                // Re-attaching to existing sessions (returning from background)
                for (TerminalSession session : sessionManager.getSessions()) {
                    session.updateTerminalSessionClient(sessionClient);
                }
                TerminalSession current = sessionManager.getCurrentSession();
                if (current != null) {
                    sessionClient.setSession(current);
                    terminalView.attachSession(current);
                }
                if (sessionListAdapter != null) {
                    sessionListAdapter.update(
                            sessionManager.getSessions(),
                            sessionManager.getCurrentIndex());
                }
                if (voiceInputManager == null) {
                    initVoiceInput();
                    initQuestInput();
                    initExtraKeys();
                }
            } else {
                // First launch — proceed with bootstrap check
                if (bootstrapInstaller.isInstalled()) {
                    onBootstrapReady();
                } else {
                    showInstallProgress();
                    bootstrapInstaller.install(new TermuxBootstrapInstaller.BootstrapCallback() {
                        @Override
                        public void onProgressUpdate(String message, int percent) {
                            if (isDestroyed()) return;
                            updateInstallProgress(message, percent);
                        }

                        @Override
                        public void onInstallComplete() {
                            if (isDestroyed()) return;
                            hideInstallProgress();
                            onBootstrapReady();
                        }

                        @Override
                        public void onInstallFailed(String error) {
                            if (isDestroyed()) return;
                            showInstallError(error);
                        }
                    });
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            terminalService = null;
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Global crash handler — chain to previous handler so Android crash dialog still works
        Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "UNCAUGHT EXCEPTION", throwable);
            if (diagnosticLog != null) {
                diagnosticLog.error("CRASH", "Uncaught exception on thread " + thread.getName(), throwable);
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable);
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

        // Control panels (GameBoy, Compact, CompactToolbar)
        panelController = new PanelController(this, rootLayout, this);

        // Wrap rootLayout in DrawerLayout
        drawerLayout = new DrawerLayout(this);
        drawerLayout.addView(rootLayout, new DrawerLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Build drawer panel
        drawerPanel = buildDrawerPanel();
        DrawerLayout.LayoutParams drawerLp = new DrawerLayout.LayoutParams(
                dp(280), ViewGroup.LayoutParams.MATCH_PARENT);
        drawerLp.gravity = Gravity.START;
        drawerLayout.addView(drawerPanel, drawerLp);

        setContentView(drawerLayout);

        // Keyboard visibility detection via layout height change
        layoutListener = () -> {
            rootLayout.getWindowVisibleDisplayFrame(visibleDisplayRect);
            int heightDiff = rootLayout.getRootView().getHeight() - visibleDisplayRect.height();
            int threshold = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 150, getResources().getDisplayMetrics());
            boolean isKeyboardNow = heightDiff > threshold;
            if (isKeyboardNow != keyboardVisible) {
                keyboardVisible = isKeyboardNow;
                onKeyboardVisibilityChanged(isKeyboardNow);
            }
        };
        rootLayout.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);

        // Apply initial panel visibility (respects fullscreen mode preference)
        updatePanelVisibility();

        // Bootstrap installer (checks if Termux environment needs install/repatch)
        bootstrapInstaller = new TermuxBootstrapInstaller(getFilesDir());

        // Start and bind TerminalService — owns sessions, survives Activity destruction
        Intent serviceIntent = new Intent(this, TerminalService.class);
        startForegroundService(serviceIntent);
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE);
    }

    private void setupSessionManagerListener() {
        sessionManager.setListener(new SessionManager.SessionChangeListener() {
            @Override
            public void onSessionSwitched(TerminalSession session) {
                sessionClient.setSession(session);
                terminalView.attachSession(session);
                session.updateTerminalSessionClient(sessionClient);
            }

            @Override
            public void onSessionAdded(TerminalSession session) {
                sessionClient.setSession(session);
                terminalView.attachSession(session);
                session.updateTerminalSessionClient(sessionClient);
            }

            @Override
            public void onSessionRemoved(TerminalSession removed, TerminalSession switchedTo) {
            }

            @Override
            public void onSessionListChanged() {
                if (sessionListAdapter != null) {
                    sessionListAdapter.update(
                            sessionManager.getSessions(),
                            sessionManager.getCurrentIndex());
                }
            }
        });
    }

    /**
     * Create a new terminal session via SessionManager.
     * SessionManager listener handles attachSession/setSession.
     */
    private void createNewSession() {
        if (sessionManager == null) return;
        try {
            String filesDir = getFilesDir().getAbsolutePath();
            String prefix = filesDir + "/usr";
            String home = filesDir + "/home";

            // Ensure libvoidterm-remap.so is available at $PREFIX/lib/ and .bashrc keeps it loaded
            copyRemapLibrary(prefix);
            ensureBashrcRemap(home, prefix);
            ensureProfileDRemap(prefix);

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

            TerminalSession session = sessionManager.createSession(
                    shell, home, args, env, sessionClient);

            Log.i(TAG, "Terminal session created: " + session.mSessionName
                    + " shell=" + shell + " home=" + home);
            if (terminalService != null) {
                terminalService.updateNotification();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create terminal session", e);
            if (diagnosticLog != null) {
                diagnosticLog.error(TAG, "Failed to create terminal session", e);
            }
        }
    }

    private String[] buildEnvironment(String prefix, String home) {
        String libDir = prefix + "/lib";

        java.util.ArrayList<String> env = new java.util.ArrayList<>();
        env.add("TERM=xterm-256color");
        env.add("COLORTERM=truecolor");
        env.add("HOME=" + home);
        env.add("PREFIX=" + prefix);
        env.add("TERMUX_PREFIX=" + prefix);
        env.add("TERMUX__PREFIX=" + prefix);
        env.add("LANG=en_US.UTF-8");
        env.add("PATH=" + prefix + "/bin:" + prefix + "/bin/applets");
        env.add("LD_LIBRARY_PATH=" + libDir);
        env.add("TMPDIR=" + prefix + "/tmp");
        env.add("SHELL=" + prefix + "/bin/bash");
        env.add("TERMINFO=" + prefix + "/share/terminfo");

        File remapLib = new File(libDir, "libvoidterm-remap.so");
        if (remapLib.exists()) {
            env.add("LD_PRELOAD=" + remapLib.getAbsolutePath());
        } else {
            Log.w(TAG, "libvoidterm-remap.so not found at " + remapLib.getAbsolutePath()
                    + ", LD_PRELOAD not set");
        }

        return env.toArray(new String[0]);
    }

    /**
     * Copy libvoidterm-remap.so from the APK's native lib dir to $PREFIX/lib/
     * so it sits alongside libtermux-exec.so and can be referenced via $PREFIX.
     */
    private void copyRemapLibrary(String prefix) {
        String nativeDir = getApplicationInfo().nativeLibraryDir;
        File src = new File(nativeDir, "libvoidterm-remap.so");
        File dstDir = new File(prefix, "lib");
        File dst = new File(dstDir, "libvoidterm-remap.so");
        if (!src.exists()) {
            Log.w(TAG, "libvoidterm-remap.so not found in nativeLibraryDir: " + nativeDir);
            File[] files = new File(nativeDir).listFiles();
            if (files != null) {
                for (File f : files) {
                    Log.w(TAG, "  native lib: " + f.getName() + " (" + f.length() + " bytes)");
                }
            } else {
                Log.w(TAG, "  nativeLibraryDir is empty or inaccessible");
            }
            return;
        }
        if (dst.exists() && dst.length() == src.length()
                && dst.lastModified() >= src.lastModified()) return;
        dstDir.mkdirs();
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            dst.setReadable(true, false);
            dst.setExecutable(true, false);
            Log.i(TAG, "Copied libvoidterm-remap.so to " + dst.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy libvoidterm-remap.so", e);
        }
    }

    /**
     * Patch an existing .bashrc to re-add libvoidterm-remap.so to LD_PRELOAD.
     * Termux's $PREFIX/etc/profile overwrites LD_PRELOAD with libtermux-exec.so,
     * so child processes (like ssh) lose the remap. This snippet appends it back.
     */
    private void ensureBashrcRemap(String home, String prefix) {
        File bashrc = new File(home, ".bashrc");
        if (!bashrc.exists()) return;
        try {
            byte[] bytes = new byte[(int) bashrc.length()];
            try (FileInputStream fis = new FileInputStream(bashrc)) {
                int offset = 0;
                while (offset < bytes.length) {
                    int read = fis.read(bytes, offset, bytes.length - offset);
                    if (read < 0) break;
                    offset += read;
                }
            }
            String content = new String(bytes);
            if (content.contains("libvoidterm-remap.so")) return;
            String snippet =
                    "\n# Ensure VoidTerm path remap stays active after Termux profile overwrites LD_PRELOAD\n" +
                    "case \"$LD_PRELOAD\" in\n" +
                    "    *libvoidterm-remap.so*) ;;\n" +
                    "    *) export LD_PRELOAD=\"${LD_PRELOAD:+$LD_PRELOAD:}$PREFIX/lib/libvoidterm-remap.so\" ;;\n" +
                    "esac\n";
            try (FileOutputStream fos = new FileOutputStream(bashrc, true)) {
                fos.write(snippet.getBytes());
            }
            Log.i(TAG, "Patched .bashrc with LD_PRELOAD remap snippet");
        } catch (Exception e) {
            Log.e(TAG, "Failed to patch .bashrc", e);
        }
    }

    /**
     * Install a profile.d script that re-adds libvoidterm-remap.so to LD_PRELOAD.
     * Unlike .bashrc (interactive bash only), profile.d is sourced by ALL login shells
     * AFTER Termux profile overwrites LD_PRELOAD with libtermux-exec.so.
     */
    private void ensureProfileDRemap(String prefix) {
        File profileD = new File(prefix, "etc/profile.d");
        profileD.mkdirs();
        File script = new File(profileD, "voidterm-remap.sh");
        String content =
                "# VoidTerm: ensure path remap library stays in LD_PRELOAD\n" +
                "case \"$LD_PRELOAD\" in\n" +
                "    *libvoidterm-remap.so*) ;;\n" +
                "    *) export LD_PRELOAD=\"${LD_PRELOAD:+$LD_PRELOAD:}$PREFIX/lib/libvoidterm-remap.so\" ;;\n" +
                "esac\n";
        try {
            if (script.exists()) {
                byte[] existing = new byte[(int) script.length()];
                try (FileInputStream fis = new FileInputStream(script)) { fis.read(existing); }
                if (new String(existing).contains("libvoidterm-remap.so")) return;
            }
            try (FileOutputStream fos = new FileOutputStream(script)) {
                fos.write(content.getBytes());
            }
            script.setReadable(true, false);
            script.setExecutable(true, false);
            Log.i(TAG, "Created profile.d remap script: " + script.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to create profile.d remap script", e);
        }
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
        gestureDetector = new KeyGestureDetector(
                new HandlerScheduler(new Handler(Looper.getMainLooper())),
                this::onGestureResolved);
        refreshGestureConfig();
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
                    if (isDestroyed()) return;
                    updateInstallProgress(message, percent);
                }

                @Override
                public void onInstallComplete() {
                    if (isDestroyed()) return;
                    hideInstallProgress();
                    startTerminalAndInit();
                }

                @Override
                public void onInstallFailed(String error) {
                    if (isDestroyed()) return;
                    showInstallError(error);
                }
            });
        } else {
            startTerminalAndInit();
        }
    }

    private void startTerminalAndInit() {
        createNewSession();
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
        panelController.hideAll();
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
        updatePanelVisibility();
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
                        if (isDestroyed()) return;
                        updateInstallProgress(message, percent);
                    }

                    @Override
                    public void onInstallComplete() {
                        if (isDestroyed()) return;
                        hideInstallProgress();
                        onBootstrapReady();
                    }

                    @Override
                    public void onInstallFailed(String err) {
                        if (isDestroyed()) return;
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
        if (gestureDetector != null && gestureDetector.onKeyDown(keyCode, event)) {
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && handleCustomBackKey()) {
            return true;
        }
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                && handleCustomVolumeKey(keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean handleCustomBackKey() {
        SharedPreferences prefs = getSharedPreferences(SettingsDialog.PREFS_NAME, MODE_PRIVATE);
        String behavior = prefs.getString(SettingsDialog.KEY_BACK_BEHAVIOR, SettingsDialog.BACK_ESCAPE);

        // Escape on the back key keeps delegating to TerminalView (unchanged).
        if (SettingsDialog.BACK_ESCAPE.equals(behavior)) {
            return false;
        }
        return dispatchKeyAction(behavior, SettingsDialog.KEY_BACK_MACRO);
    }

    private boolean handleCustomVolumeKey(int keyCode) {
        SharedPreferences prefs = getSharedPreferences(SettingsDialog.PREFS_NAME, MODE_PRIVATE);
        boolean up = keyCode == KeyEvent.KEYCODE_VOLUME_UP;
        String behaviorKey = up
                ? SettingsDialog.KEY_VOLUME_UP_BEHAVIOR : SettingsDialog.KEY_VOLUME_DOWN_BEHAVIOR;
        String behavior = prefs.getString(behaviorKey, SettingsDialog.VOLUME_DEFAULT);

        if (SettingsDialog.VOLUME_DEFAULT.equals(behavior)) {
            return false; // let the system handle volume
        }
        String macroKey = up
                ? SettingsDialog.KEY_VOLUME_UP_MACRO : SettingsDialog.KEY_VOLUME_DOWN_MACRO;
        return dispatchKeyAction(behavior, macroKey);
    }

    /**
     * Executes a behavior shared by the back/volume keys and the gesture slots.
     * Returns true if it acted; false if {@code behavior} is the "default"
     * sentinel (the caller decides what default means in its context).
     */
    private boolean dispatchKeyAction(String behavior, String macroPrefKey) {
        if (SettingsDialog.BACK_ESCAPE.equals(behavior)) {
            TerminalSession current = getCurrentSession();
            if (current != null) current.write("\033");
            return true;
        }
        if (SettingsDialog.BACK_TOGGLE_KEYBOARD.equals(behavior)) {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0);
            return true;
        }
        if (SettingsDialog.BACK_MACRO.equals(behavior)) {
            SharedPreferences prefs = getSharedPreferences(SettingsDialog.PREFS_NAME, MODE_PRIVATE);
            String macro = prefs.getString(macroPrefKey, "");
            TerminalSession current = getCurrentSession();
            if (!macro.isEmpty() && current != null) {
                MacroExecutor.execute(macro, current::write,
                        terminalView != null ? terminalView.getHandler() : null);
            }
            return true;
        }
        if (SettingsDialog.BACK_VOICE.equals(behavior)) {
            onVoiceToggle();
            return true;
        }
        if (SettingsDialog.ACTION_SESSION_NEXT.equals(behavior)) {
            cycleSession(1);
            return true;
        }
        if (SettingsDialog.ACTION_SESSION_PREV.equals(behavior)) {
            cycleSession(-1);
            return true;
        }
        if (SettingsDialog.ACTION_SESSION_DRAWER.equals(behavior)) {
            toggleSessionDrawer();
            return true;
        }
        if (SettingsDialog.ACTION_SESSION_NEW.equals(behavior)) {
            createNewSession();
            return true;
        }
        return false; // default / unknown
    }

    /** Cycle the active session by {@code delta} (+1 next, -1 previous), wrapping
     *  around both ends. No-op with fewer than two sessions. */
    private void cycleSession(int delta) {
        if (sessionManager == null) return;
        int count = sessionManager.getSessionCount();
        if (count <= 1) return;
        int next = (sessionManager.getCurrentIndex() + delta + count) % count;
        sessionManager.switchToSession(next);
    }

    /** Open the session drawer if closed, close it if open. */
    private void toggleSessionDrawer() {
        if (drawerLayout == null) return;
        if (drawerLayout.isDrawerOpen(Gravity.START)) {
            drawerLayout.closeDrawers();
        } else {
            drawerLayout.openDrawer(Gravity.START);
        }
    }

    private void adjustVolume(boolean raise) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    raise ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI);
        }
    }

    /** Rebuilds the detector's armed-set + timing from SharedPreferences. */
    private void refreshGestureConfig() {
        if (gestureDetector == null) return;
        SharedPreferences prefs = getSharedPreferences(SettingsDialog.PREFS_NAME, MODE_PRIVATE);

        Map<KeyGestureDetector.KeyId, EnumSet<KeyGestureDetector.Gesture>> armed =
                new EnumMap<>(KeyGestureDetector.KeyId.class);
        armed.put(KeyGestureDetector.KeyId.VOL_UP, armedFor(prefs,
                SettingsDialog.KEY_VOLUP_DOUBLE, SettingsDialog.KEY_VOLUP_TRIPLE, SettingsDialog.KEY_VOLUP_LONG));
        armed.put(KeyGestureDetector.KeyId.VOL_DOWN, armedFor(prefs,
                SettingsDialog.KEY_VOLDOWN_DOUBLE, SettingsDialog.KEY_VOLDOWN_TRIPLE, SettingsDialog.KEY_VOLDOWN_LONG));
        armed.put(KeyGestureDetector.KeyId.BACK, armedFor(prefs,
                SettingsDialog.KEY_BACK_DOUBLE, SettingsDialog.KEY_BACK_TRIPLE, SettingsDialog.KEY_BACK_LONG));
        armed.put(KeyGestureDetector.KeyId.COMBO, comboArmedFor(prefs));
        gestureDetector.setArmed(armed);

        gestureDetector.setTiming(GestureTiming.fromPreset(
                prefs.getString(SettingsDialog.KEY_GESTURE_TIMING_PRESET, SettingsDialog.PRESET_NORMAL)));
    }

    /** A gesture slot counts as armed when its behavior pref differs from the
     *  shared "no action" sentinel (VOLUME_DEFAULT), used for all key types. */
    private static boolean isConfigured(SharedPreferences prefs, String key) {
        return !SettingsDialog.VOLUME_DEFAULT.equals(
                prefs.getString(key, SettingsDialog.VOLUME_DEFAULT));
    }

    private static EnumSet<KeyGestureDetector.Gesture> armedFor(SharedPreferences prefs,
            String doubleKey, String tripleKey, String longKey) {
        EnumSet<KeyGestureDetector.Gesture> set =
                EnumSet.noneOf(KeyGestureDetector.Gesture.class);
        if (isConfigured(prefs, doubleKey)) set.add(KeyGestureDetector.Gesture.DOUBLE);
        if (isConfigured(prefs, tripleKey)) set.add(KeyGestureDetector.Gesture.TRIPLE);
        if (isConfigured(prefs, longKey)) set.add(KeyGestureDetector.Gesture.LONG);
        return set;
    }

    private static EnumSet<KeyGestureDetector.Gesture> comboArmedFor(SharedPreferences prefs) {
        EnumSet<KeyGestureDetector.Gesture> set =
                EnumSet.noneOf(KeyGestureDetector.Gesture.class);
        if (isConfigured(prefs, SettingsDialog.KEY_COMBO_SINGLE)) set.add(KeyGestureDetector.Gesture.SINGLE);
        if (isConfigured(prefs, SettingsDialog.KEY_COMBO_DOUBLE)) set.add(KeyGestureDetector.Gesture.DOUBLE);
        if (isConfigured(prefs, SettingsDialog.KEY_COMBO_TRIPLE)) set.add(KeyGestureDetector.Gesture.TRIPLE);
        return set;
    }

    /** Maps a resolved (key, gesture) to the behavior preference key. */
    private static String behaviorPrefKey(KeyGestureDetector.KeyId key, KeyGestureDetector.Gesture g) {
        switch (key) {
            case VOL_UP:
                if (g == KeyGestureDetector.Gesture.SINGLE) return SettingsDialog.KEY_VOLUME_UP_BEHAVIOR;
                if (g == KeyGestureDetector.Gesture.DOUBLE) return SettingsDialog.KEY_VOLUP_DOUBLE;
                if (g == KeyGestureDetector.Gesture.TRIPLE) return SettingsDialog.KEY_VOLUP_TRIPLE;
                return SettingsDialog.KEY_VOLUP_LONG;
            case VOL_DOWN:
                if (g == KeyGestureDetector.Gesture.SINGLE) return SettingsDialog.KEY_VOLUME_DOWN_BEHAVIOR;
                if (g == KeyGestureDetector.Gesture.DOUBLE) return SettingsDialog.KEY_VOLDOWN_DOUBLE;
                if (g == KeyGestureDetector.Gesture.TRIPLE) return SettingsDialog.KEY_VOLDOWN_TRIPLE;
                return SettingsDialog.KEY_VOLDOWN_LONG;
            case BACK:
                if (g == KeyGestureDetector.Gesture.SINGLE) return SettingsDialog.KEY_BACK_BEHAVIOR;
                if (g == KeyGestureDetector.Gesture.DOUBLE) return SettingsDialog.KEY_BACK_DOUBLE;
                if (g == KeyGestureDetector.Gesture.TRIPLE) return SettingsDialog.KEY_BACK_TRIPLE;
                return SettingsDialog.KEY_BACK_LONG;
            case COMBO:
            default:
                if (g == KeyGestureDetector.Gesture.DOUBLE) return SettingsDialog.KEY_COMBO_DOUBLE;
                if (g == KeyGestureDetector.Gesture.TRIPLE) return SettingsDialog.KEY_COMBO_TRIPLE;
                return SettingsDialog.KEY_COMBO_SINGLE;
        }
    }

    private static String macroPrefKey(KeyGestureDetector.KeyId key, KeyGestureDetector.Gesture g) {
        switch (key) {
            case VOL_UP:
                if (g == KeyGestureDetector.Gesture.SINGLE) return SettingsDialog.KEY_VOLUME_UP_MACRO;
                if (g == KeyGestureDetector.Gesture.DOUBLE) return SettingsDialog.KEY_VOLUP_DOUBLE_MACRO;
                if (g == KeyGestureDetector.Gesture.TRIPLE) return SettingsDialog.KEY_VOLUP_TRIPLE_MACRO;
                return SettingsDialog.KEY_VOLUP_LONG_MACRO;
            case VOL_DOWN:
                if (g == KeyGestureDetector.Gesture.SINGLE) return SettingsDialog.KEY_VOLUME_DOWN_MACRO;
                if (g == KeyGestureDetector.Gesture.DOUBLE) return SettingsDialog.KEY_VOLDOWN_DOUBLE_MACRO;
                if (g == KeyGestureDetector.Gesture.TRIPLE) return SettingsDialog.KEY_VOLDOWN_TRIPLE_MACRO;
                return SettingsDialog.KEY_VOLDOWN_LONG_MACRO;
            case BACK:
                if (g == KeyGestureDetector.Gesture.SINGLE) return SettingsDialog.KEY_BACK_MACRO;
                if (g == KeyGestureDetector.Gesture.DOUBLE) return SettingsDialog.KEY_BACK_DOUBLE_MACRO;
                if (g == KeyGestureDetector.Gesture.TRIPLE) return SettingsDialog.KEY_BACK_TRIPLE_MACRO;
                return SettingsDialog.KEY_BACK_LONG_MACRO;
            case COMBO:
            default:
                if (g == KeyGestureDetector.Gesture.DOUBLE) return SettingsDialog.KEY_COMBO_DOUBLE_MACRO;
                if (g == KeyGestureDetector.Gesture.TRIPLE) return SettingsDialog.KEY_COMBO_TRIPLE_MACRO;
                return SettingsDialog.KEY_COMBO_SINGLE_MACRO;
        }
    }

    /** Called on the main thread by the gesture detector. */
    private void onGestureResolved(KeyGestureDetector.KeyId key, KeyGestureDetector.Gesture gesture) {
        SharedPreferences prefs = getSharedPreferences(SettingsDialog.PREFS_NAME, MODE_PRIVATE);
        String behaviorKey = behaviorPrefKey(key, gesture);
        // Single tap of Back defaults to Escape; everything else defaults to "none".
        String defaultBehavior = (key == KeyGestureDetector.KeyId.BACK
                && gesture == KeyGestureDetector.Gesture.SINGLE)
                ? SettingsDialog.BACK_ESCAPE : SettingsDialog.VOLUME_DEFAULT;
        String behavior = prefs.getString(behaviorKey, defaultBehavior);

        boolean handled = dispatchKeyAction(behavior, macroPrefKey(key, gesture));
        if (!handled) {
            // behavior == default: emulate system volume for the volume keys
            if (key == KeyGestureDetector.KeyId.VOL_UP) adjustVolume(true);
            else if (key == KeyGestureDetector.KeyId.VOL_DOWN) adjustVolume(false);
            // BACK / COMBO default -> nothing
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (questInputHandler != null && questInputHandler.onKeyUp(keyCode, event)) {
            return true;
        }
        if (gestureDetector != null && gestureDetector.onKeyUp(keyCode, event)) {
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
        TerminalSession current = getCurrentSession();
        if (current != null) {
            current.write(text);
        }
    }

    @Override
    public void onVoiceToggle() {
        if (extraKeysConfig != null) {
            extraKeysConfig.onExtraKeyPressed(ExtraKeysConfig.MIC_BUTTON_KEY);
        }
    }

    private static final int REQUEST_SETTINGS = 1002;

    @Override
    public void onSettingsRequested() {
        startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SETTINGS
                && resultCode == SettingsActivity.RESULT_CUSTOMIZE_LAYOUT) {
            panelController.getControlPanel().enterEditMode();
        }
    }

    private void reloadCurrentModel() {
        if (voiceInputManager != null) {
            voiceInputManager.reloadModel(this);
        }
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
        TerminalSession current = getCurrentSession();
        if (text != null && current != null) {
            current.write(text.toString());
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
        return sessionManager != null ? sessionManager.getCurrentSession() : null;
    }

    /**
     * Get the control panel (used for edit mode entry from SettingsDialog).
     */
    public GameBoyControlPanel getControlPanel() {
        return panelController.getControlPanel();
    }

    /**
     * Get the panel controller (used by view client for modifier key consumption).
     */
    public PanelController getPanelController() {
        return panelController;
    }

    public boolean isKeyboardVisible() {
        return keyboardVisible;
    }

    /**
     * Recreate all control panels with the current theme.
     * Called from SettingsDialog after theme selection changes.
     */
    public void applyTheme() {
        lastAppliedTheme = InterfaceTheme.current(this);
        panelController.applyTheme(this);
        rebuildDrawerPanel();
        updatePanelVisibility();
    }

    /**
     * Rebuild the drawer panel with current theme colors.
     * Preserves session list data and drawer layout params.
     */
    private void rebuildDrawerPanel() {
        if (drawerLayout == null || drawerPanel == null) return;
        DrawerLayout.LayoutParams lp =
                (DrawerLayout.LayoutParams) drawerPanel.getLayoutParams();
        drawerLayout.removeView(drawerPanel);
        drawerPanel = buildDrawerPanel();
        drawerLayout.addView(drawerPanel, lp);
        // Refresh session list with current data
        if (sessionManager != null) {
            sessionListAdapter.update(
                    sessionManager.getSessions(),
                    sessionManager.getCurrentIndex());
        }
    }

    private void onKeyboardVisibilityChanged(boolean visible) {
        updatePanelVisibility();
    }

    /**
     * Set correct panel visibility based on panel mode, toolbar preference,
     * and keyboard state. Delegates to PanelController.
     */
    public void updatePanelVisibility() {
        panelController.updateVisibility(keyboardVisible);
    }

    // --- Context menu ("More" button) ---

    private static final int CONTEXT_MENU_SELECT_URL = 0;
    private static final int CONTEXT_MENU_SHARE_TEXT = 1;
    private static final int CONTEXT_MENU_RESET_TERMINAL = 2;
    private static final int CONTEXT_MENU_TOGGLE_KEYBOARD = 3;
    private static final int CONTEXT_MENU_PASTE = 4;
    private static final int CONTEXT_MENU_STYLE = 5;
    private static final int CONTEXT_MENU_SESSIONS = 6;

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
        menu.add(0, CONTEXT_MENU_SESSIONS, 0, "Sessions");
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
            case CONTEXT_MENU_RESET_TERMINAL: {
                TerminalSession resetSession = getCurrentSession();
                if (resetSession != null) {
                    resetSession.reset();
                    terminalView.invalidate();
                }
                return true;
            }
            case CONTEXT_MENU_STYLE:
                new TerminalStyleDialog(this, terminalView).show();
                return true;
            case CONTEXT_MENU_SESSIONS:
                if (drawerLayout != null) {
                    drawerLayout.openDrawer(Gravity.START);
                }
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

    // --- Session management ---

    /**
     * Build the drawer panel containing the session list and "New Session" button.
     * Programmatic layout (no XML), consistent with VoidTerm's style.
     */
    private LinearLayout buildDrawerPanel() {
        InterfaceTheme theme = InterfaceTheme.current(this);
        int bgColor = theme.drawerBg;
        int accentColor = theme.drawerAccent;
        int dividerColor = InterfaceTheme.lightenColor(bgColor, 1.4f);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(bgColor);

        // Header
        TextView header = new TextView(this);
        header.setText("Sessions");
        header.setTextColor(accentColor);
        header.setTextSize(16);
        header.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.setPadding(dp(16), dp(16), dp(16), dp(12));
        panel.addView(header);

        // Divider
        View divider = new View(this);
        divider.setBackgroundColor(dividerColor);
        panel.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // Session list
        ListView listView = new ListView(this);
        listView.setBackgroundColor(bgColor);
        listView.setDivider(null);
        listView.setDividerHeight(0);

        sessionListAdapter = new SessionListAdapter(this, new SessionListAdapter.SessionListCallback() {
            @Override
            public void onSessionTapped(int index) {
                if (sessionManager == null) return;
                sessionManager.switchToSession(index);
                drawerLayout.closeDrawers();
            }

            @Override
            public void onSessionCloseRequested(int index) {
                if (sessionManager == null) return;
                if (index >= 0 && index < sessionManager.getSessionCount()) {
                    TerminalSession toRemove = sessionManager.getSessions().get(index);
                    TerminalSession next = sessionManager.removeSession(toRemove);
                    if (next == null) {
                        createNewSession();
                    }
                }
            }

            @Override
            public void onSessionRenameRequested(int index) {
                showRenameSessionDialog(index);
            }
        });
        listView.setAdapter(sessionListAdapter);

        panel.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Bottom divider
        View bottomDivider = new View(this);
        bottomDivider.setBackgroundColor(dividerColor);
        panel.addView(bottomDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // "+ New Session" button
        TextView newBtn = new TextView(this);
        newBtn.setText("+ New Session");
        newBtn.setTextColor(accentColor);
        newBtn.setTextSize(14);
        newBtn.setTypeface(Typeface.MONOSPACE);
        newBtn.setPadding(dp(16), dp(12), dp(16), dp(12));
        newBtn.setOnClickListener(v -> {
            createNewSession();
            drawerLayout.closeDrawers();
        });
        panel.addView(newBtn);

        return panel;
    }

    /**
     * Called by TermuxTerminalSessionClient when a session's process exits.
     * Removes the session and auto-switches or creates a new one.
     */
    public void onSessionFinished(TerminalSession finishedSession) {
        if (sessionManager == null) return;
        runOnUiThread(() -> {
            TerminalSession next = sessionManager.removeSession(finishedSession);
            if (terminalService != null) {
                terminalService.updateNotification();
            }
            if (next == null) {
                createNewSession();
            }
        });
    }

    private void showRenameSessionDialog(int index) {
        if (index < 0 || index >= sessionManager.getSessionCount()) return;
        TerminalSession session = sessionManager.getSessions().get(index);

        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(session.mSessionName);
        input.setTextColor(Color.WHITE);
        input.setSelectAllOnFocus(true);
        int pad = dp(24);
        input.setPadding(pad, pad, pad, dp(8));

        new android.app.AlertDialog.Builder(this)
                .setTitle("Rename session")
                .setView(input)
                .setPositiveButton("OK", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        sessionManager.renameSession(index, name);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (voiceInputManager != null) {
            VoiceState state = voiceInputManager.getCurrentState();
            if (state == VoiceState.RECORDING) {
                voiceInputManager.onDoubleTap();
            }
        }
        if (gestureDetector != null) gestureDetector.reset();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Sync settings that may have changed in SettingsActivity
        SharedPreferences prefs = getSharedPreferences(SettingsDialog.PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(SettingsDialog.KEY_MODEL_RELOAD_REQUESTED, false)) {
            prefs.edit().putBoolean(SettingsDialog.KEY_MODEL_RELOAD_REQUESTED, false).apply();
            reloadCurrentModel();
        }
        InterfaceTheme currentTheme = InterfaceTheme.current(this);
        if (currentTheme != lastAppliedTheme) {
            applyTheme();
        }
        updatePanelVisibility();
        refreshGestureConfig();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (rootLayout != null && layoutListener != null) {
            rootLayout.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        }
        if (viewClient != null) {
            viewClient.release();
        }
        if (voiceInputManager != null) {
            voiceInputManager.destroy();
            voiceInputManager = null;
        }
        if (gestureDetector != null) {
            gestureDetector.reset();
            gestureDetector = null;
        }
        // Detach from service — sessions survive in TerminalService with HeadlessSessionClient
        if (terminalService != null) {
            terminalService.detachActivity();
        }
        if (sessionManager != null) {
            sessionManager.setListener(null);
            sessionManager = null;
        }
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        Log.i(TAG, "VoidTerm destroyed");
    }
}
