# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

VoidTerm is a Termux fork with integrated local voice input via whisper.cpp, targeting Meta Quest (ARM64 only). Users run Claude Code hands-free in VR using Push-to-Talk speech-to-text.

## Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build (unsigned)
./gradlew assembleRelease

# Clean + rebuild
./gradlew clean && ./gradlew assembleRelease

# Install on connected Quest via ADB
adb install app/build/outputs/apk/debug/app-debug.apk

# Run unit tests
./gradlew testDebugUnitTest
```

**Prerequisites:** JDK 17, Android SDK API 34, NDK r25+ (25.2.9519653), CMake 3.22.1+

**JDK note:** If default JDK is newer than 17, prefix builds with `JAVA_HOME=/usr/lib/jvm/java-17-openjdk`

**Environment variables:** `ANDROID_HOME`, `ANDROID_NDK_HOME`

**Submodule:** whisper.cpp must be initialized: `git submodule update --init --recursive`

Unit tests: `./gradlew testDebugUnitTest` (JUnit 4 + Mockito + Robolectric). Integration testing is manual on Quest devices.

## Architecture

Four Gradle modules: `:app` (main), `:terminal-emulator`, `:terminal-view`, `:termux-shared`. The last three are inherited from Termux and largely unmodified.

### Voice Pipeline (the core addition to Termux)

```
Quest Controller (A/X) → QuestInputHandler → VoiceInputManager (state machine)
Quest Microphone → AudioCapture (PCM float32 16kHz, VOICE_RECOGNITION source)
  → [optional] AudioPreprocessor (DC removal → HP → pre-emphasis → peak norm → gain)
  → WhisperBridge (JNI) → TranscriptionOverlay
User confirms (Enter) → VoiceInputCallback.onVoiceTextReady() → Terminal PTY
```

**State machine** (VoiceInputManager): `IDLE → RECORDING → TRANSCRIBING → SHOWING_RESULT → EDITING → IDLE`. Double-tap cancels recording. Escape cancels transcription. Error auto-dismisses after 3s.

### Package Layout

| Package | Role |
|---|---|
| `com.voidterm.contracts` | Shared interfaces: `VoiceState`, `VoiceInputCallback`, `TranscriptionListener`, `ControlPanel`, `ControlPanelListener` |
| `com.voidterm.voice` | Voice system: `VoiceInputManager`, `AudioCapture`, `AudioPreprocessor`, `AudioConfig`, `WhisperBridge`, `TranscriptionOverlay` |
| `com.voidterm.input` | Controller mapping: `QuestInputHandler` |
| `com.voidterm.app` | Activity, UI, styling: `TermuxActivity`, `SessionManager`, `SessionListAdapter`, `GameBoyControlPanel`, `CompactToolbar`, `MacroExecutor`, `MacroEditDialog`, `TerminalStyleDialog`, `SettingsDialog`, `SettingsActivity`, `InterfaceTheme`, `ExtraKeysConfig` |

### JNI Layer

`app/src/main/jni/whisper_jni.cpp` bridges to whisper.cpp (git submodule at `app/src/main/jni/whisper.cpp`, pinned to v1.7.3). Six native methods on `WhisperBridge`: `nativeInit`, `nativeTranscribe`, `nativeFree`, `nativeIsLoaded`, `nativeAbort` (cooperative cancellation via ggml abort callback), `nativeGetSystemInfo` (NEON/FP16/backend info).

### Native Build (CMakeLists.txt)

Sources are compiled directly into one `.so` (matching official whisper.android examples), bypassing the ggml CMake build system. Two library variants are built:
- `whisper_jni` — baseline ARM NEON (always available on arm64)
- `whisper_jni_v8fp16` — ARMv8.2-A half-precision (`-march=armv8.2-a+fp16`, ~15-30% faster on Quest XR2)

Critical build details:
- `-O3` is always applied (even in Debug) — without it, NEON matmul is ~15-20x slower
- `GGML_USE_CPU` define is required to register the CPU backend in `ggml-backend-reg.cpp`
- OpenMP is not used — ggml uses its own pthreads internally

`CpuInfo` detects optimal thread count by reading `/sys/devices/system/cpu/` frequencies (drops lowest-freq cores for big.LITTLE), falls back to `(availableProcessors + 1) / 2`.

### Threading Model

- **Main thread:** UI, overlay, state dispatch
- **AudioCapture-Thread:** AudioRecord read loop (active only during recording)
- **WhisperBridge-Transcribe:** whisper.cpp inference (burst 2-4s)
- **WhisperBridge-ModelLoad:** one-time model loading from assets
- **DeviceProfiler:** one-time benchmark after model load (1s synthetic audio)

Thread safety: `VoiceInputManager` uses `stateLock` for state transitions, dispatches outside lock. `WhisperBridge` uses `AtomicBoolean` guards for concurrent call rejection. `AudioCapture` uses `lock` for start/stop.

## Key Contracts

Changes to files in `com.voidterm.contracts` affect the entire voice pipeline. `VoiceInputCallback` is implemented by TermuxActivity (injects text into PTY). `TranscriptionListener` is implemented by `VoiceInputManager` (receives overlay user actions).

### Context Menu & Style System

`TermuxActivity` registers `TerminalView` for context menus (`registerForContextMenu`). The "More" button in text selection triggers `showContextMenu()` which is handled by `onCreateContextMenu`/`onContextItemSelected` in the Activity. Options: Paste, Share, Select URL, Style, Reset, Toggle keyboard.

`TerminalStyleDialog` manages terminal appearance (font size, font family, color scheme). Preferences persist via `SharedPreferences` ("voidterm_style"). Bundled fonts (TTF) are in `app/src/main/assets/fonts/`. Colors are applied directly to `mEmulator.mColors.mCurrentColors[]` indices 256 (fg), 257 (bg), 258 (cursor). Saved styles are restored on session start via `applySavedStyle()`.

### GameBoy Control Panel

`GameBoyControlPanel` provides a touchscreen control panel styled like a Game Boy. Includes D-pad, modifier keys (Ctrl, Shift, Esc), Tab/S-Tab, Enter/S-Enter, macro buttons, and a burger menu button (☰). Communicates with `TermuxActivity` via `ControlPanelListener` interface (`onSendToTerminal`, `onVoiceToggle`, `onSettingsRequested`). Key codes: Enter sends `\r` (submit), S-Enter sends `\n` (newline without submit), TAB sends `\t` (respects SHF state for backtab), S-TAB sends `\033[Z` (backtab).

`CompactPanel` is a 170dp panel with 4 rows of 6 buttons, showing all 12 macros at once (no paging). Row 1 (modifiers): ESC, CTL, TAB, S-TAB, S-ENT, burger menu (☰). Row 2 (navigation): ◀, ▲, ▼, ▶, Enter (↵), STT (🎤). Rows 3-4 (macros): M1-M6 and M7-M12. No Shift button — dedicated S-TAB and S-ENT cover all shift uses. `isShiftActive()` always returns false. `setCurrentMacroPage()` reloads macro labels from `MacroStore` for cross-panel sync. Same `ControlPanelListener` interface.

`CompactToolbar` is a 48dp horizontal bar shown above the soft keyboard (or permanently in fullscreen mode). Main row: ESC, CTL, SHF, TAB, arrows (◀▲▼▶), Enter (↵), STT (🎤), burger menu (☰). Swipe left for macro pages (3 pages of 4 buttons). Same `ControlPanelListener` interface as `GameBoyControlPanel`.

Panel mode (`KEY_PANEL_MODE` in SharedPreferences) controls which panel is shown: `"gameboy"` (default), `"compact"`, or `"fullscreen"`. In gameboy/compact modes, `CompactToolbar` replaces the main panel when the keyboard is visible (if toolbar enabled). In fullscreen mode, only `CompactToolbar` is shown. `TermuxActivity.updatePanelVisibility()` handles all transitions, syncing modifier and macro page state between panels. Migration from the old `KEY_FULLSCREEN_MODE` boolean is handled by `SettingsDialog.migratePanelMode()`.

All three panels use `onDetachedFromWindow()` to cancel arrow repeat runnables and unregister `SharedPreferences` listeners. Arrow repeat uses tracked `activeRepeatRunnable`/`activeRepeatView` fields with explicit `cancelRepeat()` on touch-up and detach.

### Macro System

12 user-configurable macros displayed as 3 pages of 4 buttons in both `GameBoyControlPanel` (page cycle button above vertical stack) and `CompactToolbar` (page indicator button + swipe navigation across pages). Edited via long-press → `MacroEditDialog`. Persistence centralized in `MacroStore` (SharedPreferences "voidterm_macros", JSON array of 12 objects). Migrates automatically from the old 4-macro format by preserving existing macros and appending 8 defaults.

`MacroExecutor` parses and executes macro commands. Two modes:
- **Plain text** (no `{`): sends text + `\r` (backward compatible)
- **Key combinations** (contains `{`): parses `{tag}` syntax into terminal escape sequences

Supported tags: `{esc}`, `{enter}`, `{tab}`, `{up}`, `{down}`, `{left}`, `{right}`, `{home}`, `{end}`, `{f1}`-`{f12}`, `{ctrl+a}`-`{ctrl+z}`, `{shift+KEY}`, `{alt+KEY}`, `{wait:N}` (delay ms). Escaped braces: `{{` → `{`, `}}` → `}`.

### Interface Theming

`InterfaceTheme` enum defines 4 themes (GAMEBOY, DARK_GAMEBOY, ATOMIC_PURPLE, HACKERBOY), each with 7 panel colors + 2 drawer colors (`drawerBg`, `drawerAccent`). Static helpers: `darkenColor()`, `lightenColor()`, `isLightColor()` (luminance-based light/dark detection). Persisted via `SettingsDialog.KEY_THEME`.

The session drawer (`TermuxActivity.buildDrawerPanel()`) and `SessionListAdapter` use `drawerBg`/`drawerAccent` for theme-consistent colors. Text color adapts via `isLightColor(drawerBg)` — dark text on light backgrounds (GameBoy cream), light text on dark backgrounds. `TermuxActivity.rebuildDrawerPanel()` replaces the drawer panel on theme change.

`SettingsActivity` (full-screen, programmatic layout) computes adaptive colors from `theme.background` via `computeDerivedColors()`: `surfaceColor`, `bodyColor`, `textColor`, `mutedColor`, `hintColor`. Theme change triggers `recreate()` with `savedInstanceState` to preserve expanded accordion section.

### Settings & Model Selection

`SettingsActivity` (full-screen Activity, programmatic layout) lets users select a custom whisper.cpp model file via Android's `ACTION_OPEN_DOCUMENT` file picker. `SettingsDialog` holds all preference key constants and label/value arrays. The selected file is copied to `{filesDir}/models/`, its name persisted in `SharedPreferences` ("voidterm_settings" / "whisper_model_name"), and hot-reloaded via `VoiceInputManager.reloadModel()`. Default model: `ggml-base.bin` (bundled in assets). `WhisperBridge.loadModel()` checks `{filesDir}/models/` first, falls back to assets, and returns a clear error if neither exists. GPU toggle (default off) controls `whisper_context_params.use_gpu`. `WhisperBridge` selects the FP16 library variant at runtime if `Build.VERSION.SDK_INT >= 27` (ARMv8.2-A support). `TermuxActivity.onResume()` calls `applyTheme()` to sync theme after returning from SettingsActivity.

### Transcription Settings

Configurable whisper.cpp transcription parameters in `SettingsDialog` "Transcription" section. All settings persisted in `SharedPreferences` ("voidterm_settings") and read fresh at each transcription via `VoiceInputManager.buildWhisperConfig()` → `WhisperConfig` (immutable data class in `com.voidterm.voice`).

| Key | Type | Default | Description |
|---|---|---|---|
| `whisper_language` | String | `"en"` | ISO 639-1 code or `"auto"` for auto-detect |
| `whisper_translate` | boolean | `false` | Translate output to English |
| `whisper_initial_prompt` | String | `""` | Vocabulary hints for domain terms |
| `whisper_temperature` | float | `0.0f` | Sampling temperature (0=precise, 1=creative) |
| `whisper_beam_search` | boolean | `false` | Use beam search instead of greedy sampling |
| `whisper_beam_size` | int | `5` | Beam width (2-8, only when beam search enabled) |
| `whisper_thread_override` | int | `0` | Thread count (0=auto via CpuInfo) |
| `whisper_suppress_non_speech` | boolean | `false` | Filter non-speech tokens |
| `whisper_streaming` | boolean | `false` | Show text progressively during transcription |

Data flow: `SharedPreferences → WhisperConfig → WhisperBridge.transcribe() → nativeTranscribe() JNI → whisper_full_params`. The JNI layer receives flattened primitives (not the config object) to avoid `GetFieldID` boilerplate. Advanced settings (temperature, beam search, threads, suppress) are hidden behind a collapsible "Advanced..." button in the UI. Streaming toggle is visible in the main Transcription section (not behind Advanced).

### Audio Preprocessing (AudioConfig)

`AudioConfig` (immutable data class in `com.voidterm.voice`) holds configurable preprocessing parameters. `AudioConfig.DEFAULT` is the single source of truth for all default values — `SharedPreferences` fallbacks in `VoiceInputManager` and `AudioDebugDialog` reference `AudioConfig.DEFAULT` fields.

| Key | Type | Default | Description |
|---|---|---|---|
| `audio_gain` | float | `1.0f` | Output gain (applied AFTER normalization as volume control) |
| `audio_pre_emphasis` | float | `0.97f` | Pre-emphasis coefficient (0=off, 0.97=standard speech) |
| `audio_hp_cutoff` | int | `80` | High-pass filter cutoff in Hz |
| `audio_norm_target` | float | `0.9f` | Peak normalization target level |

Pipeline order: DC removal → HP filter → pre-emphasis → peak normalization → output gain. Gain is intentionally placed AFTER normalization — applying it before would cause clipping distortion without benefit (normalization undoes non-clipping gain). The `hpAlpha()` method on `AudioConfig` computes the IIR filter coefficient from the cutoff frequency.

`AudioDebugDialog` provides real-time A/B testing: record a test clip, adjust parameters via spinners, hear the difference. Spinners persist changes to `SharedPreferences` immediately. `AudioPreprocessor.processWithDiagnostics()` returns per-stage stats for the debug display.

Data flow: `SharedPreferences → AudioConfig → AudioPreprocessor.process() → whisper.cpp input`. Cached in `VoiceInputManager.cachedAudioConfig` (volatile, invalidated by `OnSharedPreferenceChangeListener`).

### Streaming Transcription

When `whisper_streaming` is enabled, decoded text appears progressively in the overlay during the `TRANSCRIBING` state instead of showing a spinner. Uses whisper.cpp's `new_segment_callback` mechanism.

Data flow: `whisper_full() → new_segment_callback → StreamCallbackData (C++) → JNI CallVoidMethod → WhisperBridge.onNativeSegment() → mainHandler.post → Callback.onPartialResult() → VoiceInputManager → TranscriptionOverlay.updateStreamingText()`.

Key implementation details:
- When streaming, `GetFloatArrayRegion` (copy) replaces `GetPrimitiveArrayCritical` (zero-copy) because the latter blocks the GC and forbids JNI callbacks
- `params.single_segment = false` enables multiple segments, triggering the callback progressively
- The overlay reuses `resultContainer` with hidden Send/Cancel buttons during streaming; buttons reappear when `showTranscription()` is called with the final text
- Benchmark (`DeviceProfiler`) always passes `streaming=false` to avoid callback overhead during profiling
- Default is OFF — behavior is 100% identical to pre-streaming when disabled
- Streaming sends text directly to terminal PTY without review/cancel overlay — SettingsDialog shows an orange warning when enabled

### Auto-Tuning (DeviceProfiler)

`DeviceProfiler` (stateless utility in `com.voidterm.voice`) auto-tunes transcription parameters after model load. Runs a 1s synthetic audio benchmark (440Hz sine, greedy decode) once per model, classifies the device into a performance tier, and writes optimal defaults.

| Tier | Benchmark (1s audio) | beam_search | beam_size | proportional_context | suppress_non_speech |
|---|---|---|---|---|---|
| FAST | < 600ms | true | 5 | true | true |
| MEDIUM | 600-1200ms | true | 3 | true | true |
| SLOW | > 1200ms | false | -- | true | true |

Results cached in SharedPreferences (`autotune_model`, `autotune_benchmark_ms`, `autotune_tier`). User manual changes tracked in `user_overrides` (StringSet) — auto-tuning never overwrites overridden keys. "Reset to Auto" button in SettingsDialog clears overrides and forces re-profiling. One-time migration detects pre-existing parameter changes and marks them as user overrides.

### Haptic Feedback

Haptic feedback on button presses is toggled via checkbox in `SettingsDialog` Interface section. Persisted in `SharedPreferences` ("voidterm_settings" / "haptic_feedback", default `true`). `GameBoyControlPanel` and `CompactToolbar` cache the value in a `volatile boolean hapticEnabled` field, invalidated via `OnSharedPreferenceChangeListener` (same pattern as `VoidTermTerminalViewClient`). The static `SettingsDialog.isHapticEnabled(Context)` method still exists for other callers but should not be used in hot paths.

### Panel Mode

Panel mode is selected via a Spinner in `SettingsDialog` Interface section. Three modes: "GameBoy Panel" (`gameboy`, default), "Compact Panel" (`compact`), "Fullscreen (toolbar only)" (`fullscreen`). Persisted in `SharedPreferences` ("voidterm_settings" / "panel_mode"). In fullscreen mode, the "Compact toolbar above keyboard" checkbox is disabled (toolbar is forced on). The "Customize Layout" button is disabled when not in GameBoy mode. Panel visibility is centralized in `PanelController.updateVisibility()`, which tracks the active panel via an `activePanel` field (type `ControlPanel`). `VoidTermTerminalViewClient.readControlKey()`/`readShiftKey()` delegate to `PanelController.consumeCtrl()`/`consumeShift()` for O(1) modifier consumption from the active panel.

### Tap-to-Toggle Keyboard

Tapping the terminal view toggles the soft keyboard via `VoidTermTerminalViewClient.onSingleTapUp()`. This behavior is controlled by `SharedPreferences` ("voidterm_settings" / "tap_toggle_keyboard", default `true`). Configurable via checkbox in `SettingsDialog` Interface section.

### Back Key Behavior

The back key behavior is configurable via `SettingsDialog` (Spinner). Three modes persisted in `SharedPreferences` ("voidterm_settings" / "back_key_behavior"):
- **Escape** (default): `VoidTermTerminalViewClient.shouldBackButtonBeMappedToEscape()` returns `true`, TerminalView sends `\033`.
- **Toggle Keyboard**: `shouldBackButtonBeMappedToEscape()` returns `false`, `TermuxActivity.handleCustomBackKey()` toggles soft input.
- **Macro**: same dispatch path, executes a user-defined macro command (stored in "back_key_macro") via `MacroExecutor`. Supports full `{tag}` syntax.

### Lifecycle & Error Recovery

`TermuxActivity` implements `onPause()` to cancel active voice recording when backgrounded (prevents microphone leak). `onResume()` is minimal — the voice system stays loaded. The uncaught exception handler chains to Android's default handler after logging.

`VoiceInputManager`'s pipeline thread (`VoiceInput-Pipeline`) wraps its body in try-catch to prevent the state machine from getting stuck in `TRANSCRIBING` on unexpected exceptions — transitions to `ERROR` with auto-dismiss.

`WhisperBridge.release()` guards `nativeFree()` behind a `thread.isAlive()` check after join — leak over crash. When `isDestroyed` is true during a pending callback, `onError` is posted instead of silently dropping the callback.

Bootstrap callbacks in `TermuxActivity` guard with `if (isDestroyed()) return;` to prevent UI operations after Activity destruction. `TermuxActivity.onDestroy()` calls `viewClient.release()` to unregister its `SharedPreferences` listener.

### Multi-Session Support (Left Drawer)

`SessionManager` manages a `List<TerminalSession>` with a current index. Sessions are created via `createSession()` (used by `TermuxActivity.createNewSession()`), switched via `switchToSession(int)`, and removed via `removeSession(TerminalSession)`. When the last session is removed, the caller creates a new one automatically. `SessionChangeListener` notifies `TermuxActivity` on add/switch/remove/list-change events.

The drawer UI is a `DrawerLayout` that wraps `rootLayout`. The drawer panel (280dp, `Gravity.START`) contains a "Sessions" header, a `ListView` backed by `SessionListAdapter`, and a "+ New Session" button. Drawer is accessible via swipe-from-left or the "Sessions" entry in the context menu.

`SessionListAdapter` (`BaseAdapter`, programmatic layout) shows each session as: green/gray dot (active indicator) + session name (monospace) + close button. Tapping switches sessions, close button removes sessions.

`TermuxTerminalSessionClient` guards `onTextChanged`/`onColorsChanged` to only update the terminal view for the active session. `onSessionFinished` delegates to `TermuxActivity.onSessionFinished()` which calls `sessionManager.removeSession()`.

Voice pipeline, control panels, and macros all use `getCurrentSession()` which delegates to `sessionManager.getCurrentSession()` — no changes needed in those systems.

### Panel State Synchronization

All three panels implement the `ControlPanel` interface (`com.voidterm.contracts`), which defines the modifier and macro state API: `isCtrlActive()`, `setCtrlActive(boolean)`, `resetCtrl()`, `isShiftActive()`, `setShiftActive(boolean)`, `resetShift()`, `getCurrentMacroPage()`, `setCurrentMacroPage(int)`. `PanelController.updateVisibility()` syncs state across panel transitions via `showPanel()` helper, which restores modifier/macro state and tracks the `activePanel` reference. CompactPanel has no shift button — shift methods are no-ops (`isShiftActive()` returns false). CompactPanel shows all 12 macros (no paging) — `getCurrentMacroPage()` returns 0, `setCurrentMacroPage()` reloads macro labels for cross-panel sync. CompactPanel and CompactToolbar share button factories (`PanelUtils.makeCompactButton()`, `PanelUtils.makeCompactButtonDrawable()`) — GameBoy keeps its own (different shape system).

### SharedPreferences Caching

`VoidTermTerminalViewClient` caches preference values (`tapToggleKeyboard`, `backKeyBehavior`) in volatile fields and invalidates via `OnSharedPreferenceChangeListener`, following the same pattern as `VoiceInputManager`. `GameBoyControlPanel` and `CompactToolbar` use the same pattern for `hapticEnabled`. Avoids I/O on every tap, back key, or button press event.

### SharedPreferences Key Constants

All preference keys for `voidterm_settings` are centralized as `public static final` constants in `SettingsDialog` (e.g. `SettingsDialog.KEY_WHISPER_LANGUAGE`). Other classes (VoiceInputManager, DeviceProfiler, InterfaceTheme) reference these constants — never raw strings. This ensures the compiler catches key renames.

### Path Remapping (com.termux → com.voidterm)

Termux binaries have `/data/data/com.termux/files/usr` hardcoded in ELF .rodata. Three-layer remapping strategy:

1. **ELF patcher** (`TermuxBootstrapInstaller.patchTermuxPaths()`): Binary replacement `com.termux/files` → `com.voidterm/fil` (same-length, 16 chars). Symlink `fil → files` resolves paths. Also patches `com.termux/cache` → `com.voidterm/cac`. Text files get variable-length `com.termux` → `com.voidterm` replacement. Controlled by `PATCH_VERSION` (currently 11) — bump to force re-scan of all `$PREFIX` files.

2. **DPkg post-invoke hook** (`$PREFIX/lib/voidterm-patch-new.sh`): Registered via `$PREFIX/etc/apt/apt.conf.d/99-voidterm-patcher.conf`. Auto-patches ELF (via `perl -pi`) and text files (via `grep -I` + `sed -i`) after each `apt install`. Only processes `.list` files modified in last 2 minutes. **Critical**: apt config must reference `prefixDir` path (not `stagingDir`) — the staging dir is renamed after bootstrap install.

3. **LD_PRELOAD** (`libvoidterm-remap.so` / `voidterm_remap.c`): 14 file-access hooks (open, mkdir, stat, etc.) remap `/data/data/com.termux/` → `/data/data/com.voidterm/` at runtime. Copied from APK native libs to `$PREFIX/lib/` by `TermuxActivity.copyRemapLibrary()`. Set in initial environment by `buildEnvironment()`. `.bashrc` snippet re-adds it after Termux profile overwrites `LD_PRELOAD`. Does NOT hook `execve()` — `libtermux-exec.so` bypasses PLT for exec, making our hook unreachable.

Build requirements for LD_PRELOAD: `extractNativeLibs=true` in AndroidManifest.xml, `useLegacyPackaging=true` in build.gradle (ensures .so files are extractable, not stored compressed in APK).

## Code Review & Remediation

`plans/CODE_REVIEW.md` documents 61 findings from a 5-agent parallel code review (concurrency, JNI, UI, architecture, config). Phases 1-4 completed (36 findings fixed). Phase 5 (interface commune panels) completed. Completion reports in `plans/completed/`.

## Code Style

- Java: 4 spaces, `camelCase` methods/variables, `PascalCase` classes
- JNI/C++: 4 spaces, follow `whisper_jni.cpp` conventions
- No unused imports or variables

## Branching

`main` (stable releases), `develop` (integration), `feature/*` (individual work). PRs target `develop`.
