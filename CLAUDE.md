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

**State machine** (VoiceInputManager): `IDLE → RECORDING → TRANSCRIBING → SHOWING_RESULT → EDITING → IDLE`. Double-tap cancels recording. Escape cancels transcription (also aborts native whisper via `WhisperBridge.abort()`). Error auto-dismisses after 3s. Minimum audio duration check (0.5s) prevents hallucinations from ultra-short PTT. `reloadModel()` is gated on IDLE state to prevent bridge-release race conditions. `dispatchStateChange()` checks a `destroyed` flag to prevent stale callbacks on destroyed activities.

### Package Layout

| Package | Role |
|---|---|
| `com.voidterm.contracts` | Shared interfaces: `VoiceState`, `VoiceInputCallback`, `TranscriptionListener`, `ControlPanel`, `ControlPanelListener` |
| `com.voidterm.voice` | Voice system: `VoiceInputManager`, `AudioCapture`, `AudioPreprocessor`, `AudioConfig`, `AudioChunker`, `WhisperBridge`, `WhisperEngine`, `WhisperConfig`, `ParakeetEngine`, `ParakeetConfig`, `TranscriptionOverlay` |
| `com.voidterm.input` | Controller & physical-key input: `QuestInputHandler`, `KeyGestureDetector`, `GestureTiming`, `Scheduler`/`HandlerScheduler` |
| `com.voidterm.app` | Activity, UI, styling: `TermuxActivity`, `SessionManager`, `SessionListAdapter`, `GameBoyControlPanel`, `CompactToolbar`, `MacroExecutor`, `MacroEditDialog`, `TerminalStyleDialog`, `SettingsDialog`, `SettingsActivity`, `InterfaceTheme`, `ExtraKeysConfig` |

### JNI Layer

`app/src/main/jni/whisper_jni.cpp` bridges to whisper.cpp (git submodule at `app/src/main/jni/whisper.cpp`, pinned to v1.7.3). Six native methods on `WhisperBridge`: `nativeInit`, `nativeTranscribe`, `nativeFree`, `nativeIsLoaded`, `nativeAbort` (cooperative cancellation via ggml abort callback), `nativeGetSystemInfo` (NEON/FP16/backend info). JNI layer includes null guards on `language` and `audioData` parameters. The `g_abort_flag` is process-global (assumes single active transcription, enforced by Java-side `isTranscribing` CAS).

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

Thread safety: `VoiceInputManager` uses `stateLock` for state transitions, dispatches outside lock. Precondition checks (model loaded, audio start) happen inside the lock before committing the RECORDING state. `WhisperBridge` uses `AtomicBoolean` guards for concurrent call rejection; `abort()` exposes `nativeAbort()` for external callers. `AudioCapture` uses `lock` for start/stop; `stopRecording()` calls `audioRecord.stop()` before thread join to unblock `READ_BLOCKING`.

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

All three panels register `SharedPreferences` listeners in `onAttachedToWindow()` and unregister in `onDetachedFromWindow()` (prevents leak if panel is created but never attached). Arrow repeat uses tracked `activeRepeatRunnable`/`activeRepeatView` fields with explicit `cancelRepeat()` on touch-up and detach.

### Macro System

12 user-configurable macros displayed as 3 pages of 4 buttons in both `GameBoyControlPanel` (page cycle button above vertical stack) and `CompactToolbar` (page indicator button + swipe navigation across pages). Edited via long-press → `MacroEditDialog`. Persistence centralized in `MacroStore` (SharedPreferences "voidterm_macros", JSON array of 12 objects). Migrates automatically from the old 4-macro format by preserving existing macros and appending 8 defaults.

`MacroExecutor` parses and executes macro commands. Two modes:
- **Plain text** (no `{`): sends text + `\r` (backward compatible)
- **Key combinations** (contains `{`): parses `{tag}` syntax into terminal escape sequences

Supported tags: `{esc}`, `{enter}`, `{tab}`, `{up}`, `{down}`, `{left}`, `{right}`, `{home}`, `{end}`, `{f1}`-`{f12}`, `{ctrl+a}`-`{ctrl+z}`, `{shift+KEY}`, `{alt+KEY}`, `{wait:N}` (delay ms). Escaped braces: `{{` → `{`, `}}` → `}`.

### Interface Theming

`InterfaceTheme` enum defines 4 themes (GAMEBOY, DARK_GAMEBOY, ATOMIC_PURPLE, HACKERBOY), each with 7 panel colors + 2 drawer colors (`drawerBg`, `drawerAccent`). Static helpers: `darkenColor()` (clamps to 0), `lightenColor()` (clamps to 255), `isLightColor()` (luminance-based light/dark detection). Persisted via `SettingsDialog.KEY_THEME`.

The session drawer (`TermuxActivity.buildDrawerPanel()`) and `SessionListAdapter` use `drawerBg`/`drawerAccent` for theme-consistent colors. Text color adapts via `isLightColor(drawerBg)` — dark text on light backgrounds (GameBoy cream), light text on dark backgrounds. `TermuxActivity.rebuildDrawerPanel()` replaces the drawer panel on theme change.

`SettingsActivity` (full-screen, programmatic layout) computes adaptive colors from `theme.background` via `computeDerivedColors()`: `surfaceColor`, `bodyColor`, `textColor`, `mutedColor`, `hintColor`. Theme change triggers `recreate()` with `savedInstanceState` to preserve expanded accordion section.

### Settings & Model Selection

`SettingsActivity` (full-screen Activity, programmatic layout) lets users select a custom whisper.cpp model file via Android's `ACTION_OPEN_DOCUMENT` file picker. `SettingsDialog` holds all preference key constants and label/value arrays. The selected file is copied to `{filesDir}/models/`, its name persisted in `SharedPreferences` ("voidterm_settings" / "whisper_model_name"), and hot-reloaded via `VoiceInputManager.reloadModel()`. Default model: `ggml-base.bin` (bundled in assets). `WhisperBridge.loadModel()` checks `{filesDir}/models/` first, falls back to assets, and returns a clear error if neither exists. GPU toggle (default off) controls `whisper_context_params.use_gpu`. `WhisperBridge` selects the FP16 library variant at runtime if `Build.VERSION.SDK_INT >= 27` (ARMv8.2-A support). `TermuxActivity.onResume()` calls `applyTheme()` to sync theme after returning from SettingsActivity.

### Parakeet Model Download (background)

The Parakeet TDT v3 model (~534 MB, 4 ONNX files from HuggingFace) downloads via a **dedicated foreground service**, `ParakeetDownloadService` (`com.voidterm.app`), so it survives leaving the Settings screen and app backgrounding. Responsibility split (SRP): the service is the **mechanism** (foreground lifecycle, progress notification, `PARTIAL_WAKE_LOCK` against device sleep, cancellation); `ParakeetModelManager.download(ctx, callback, AtomicBoolean cancelFlag)` is the **logic** (blocking HTTP transfer on the caller's thread, no `Handler`/`Looper` — the service owns dispatch). It is separate from `TerminalService` on purpose: `TerminalService.stopIfNoSessions()` would otherwise kill the download.

- `SettingsActivity` starts it via `startForegroundService(ACTION_START_DOWNLOAD)` and observes progress through a private broadcast (`BROADCAST_PROGRESS/COMPLETE/ERROR`, registered `RECEIVER_NOT_EXPORTED` in `onResume`/`onPause`). On reopen mid-download it seeds its UI from `ParakeetDownloadService.isRunning()` + `lastProgressText()`.
- Notification (channel `voidterm_download`, ID **2** ≠ TerminalService's ID 1) shows per-file progress + a **Cancel** action; tapping opens Settings. On complete it sets `KEY_MODEL_RELOAD_REQUESTED`, posts a dismissable "ready" notification, and `stopSelf()`.
- **Cancellation**: `ACTION_CANCEL_DOWNLOAD` trips `cancelFlag`; the download loop checks it between files and inside the byte loop (`InterruptedIOException`), deletes the partial `.tmp`, and reports the `ParakeetModelManager.CANCELLED` sentinel (silent teardown, no error notification).
- **Resume**: completed files are skipped; an interrupted file restarts from scratch (no HTTP Range). Because completed files are skipped, "Re-download Models" is a no-op when all files are present — a "🗑 Delete Models" button (`confirmDeleteModels()` → `ParakeetModelManager.deleteModels()`, behind an `AlertDialog`) clears them first. The delete button is hidden while a download is running and when no models exist. Manifest: `WAKE_LOCK` permission + a plain `<service>` declaration (no `foregroundServiceType` — `targetSdk 28` doesn't require it).

### Parakeet Config, Chunking & ONNX Tuning

`ParakeetConfig` (immutable data class in `com.voidterm.voice`, `DEFAULT` = single source of defaults) mirrors `WhisperConfig`: `ParakeetEngine` caches it in a `volatile` field invalidated by an `OnSharedPreferenceChangeListener` (`CONFIG_KEYS`), with `readConfig`/`buildConfig`. All keys are `parakeet_*` constants in `SettingsDialog`; UI is a "Parakeet Advanced..." collapsible in `SettingsActivity.addParakeetAdvanced()` (shown only when the Parakeet engine is selected — the transcription section is fully rebuilt on engine switch). Fields: `threadCount` (0=auto via `CpuInfo.getPreferredThreadCount()`), `maxWindowSamples` (chunk window, clamped 10–30s — raising it past the tested ceiling re-introduces the OOM chunking prevents), `overlapSamples`, `silenceThreshold`, `searchBandSamples` (testability seam, not user-facing), `maxTokensPerStep` (promotes the former `MAX_TOKENS_PER_STEP` constant).

**ONNX session tuning** (in `loadModel`): `setOptimizationLevel(ALL_OPT)` always-on + `setIntraOpNumThreads` (config or auto). Changing threads sets `KEY_MODEL_RELOAD_REQUESTED`. Honest scope — only these two ONNX knobs are real wins; inter-op / opt-level choice are not exposed. A startup spike logs `OrtEnvironment.getAvailableProviders()` to assess whether an XNNPACK toggle is worth building later (NNAPI is not pursued without on-device proof — see `plans/`).

**Chunking** (`AudioChunker`, pure/stateless/unit-tested in `AudioChunkerTest`): audio that fits one encoder pass (`fitsInOneWindow`) is a single chunk referencing the original array — **byte-for-byte identical to the pre-chunking path**. Longer audio is split at SILENCE (lowest-RMS frame below `silenceThreshold` in the trailing `searchBand`) so sentences aren't cut mid-stream; continuous speech with no silence hard-splits with an `overlap` region flagged `isFallbackSplit`. An undersized trailing chunk (< 0.5s) is merged into the previous one so it can't dodge the minimum-duration hallucination guard. `ParakeetEngine.runChunked()` runs the **full pipeline per chunk** (preprocess→encode→decode — required for the memory win; decoder state reset per chunk is correct since a silence is a natural state reset), checks `abortFlag` **between** chunks, scales the watchdog by chunk count, emits `onProgress` per chunk, and merges chunk texts into a rolling transcript — de-duplicating leading words on fallback chunks via `dedupLeading()` (word-level, case-insensitive, bounded). Still **no progressive display** (final merged text only, per design); direct-send injects the final text. `AudioCapture` no longer pre-allocates a fixed buffer or hard-caps at 30s: it accumulates 100ms read chunks into a `List<float[]>` concatenated in `stopRecording()`, with only a 2-minute safety ceiling (engine-agnostic, distinct from the Parakeet chunk window). whisper.cpp self-windows internally, so it is unaffected by the lifted cap.

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
| `voice_direct_send` | boolean | `false` | Bypass the review overlay — see "Direct Send & Auto-Submit" below (engine-agnostic) |
| `voice_auto_submit` | boolean | `false` | Press Enter (`\r`) after the final text (only with direct-send) |

Data flow: `SharedPreferences → WhisperConfig → WhisperBridge.transcribe() → nativeTranscribe() JNI → whisper_full_params`. The JNI layer receives flattened primitives (not the config object) to avoid `GetFieldID` boilerplate. Advanced settings (temperature, beam search, threads, suppress) are hidden behind a collapsible "Advanced..." button in the UI. The direct-send and auto-submit toggles are visible in the main Transcription section for both engines (not behind Advanced) — see "Direct Send & Auto-Submit" below.

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

### Direct Send & Auto-Submit

Direct-send is an **engine capability** (`TranscriptionEngine.isDirectToTerminal()`), not a whisper-only flag. When enabled (`voice_direct_send`), the transcribed text is injected straight into the terminal PTY, bypassing the `SHOWING_RESULT` review/cancel overlay. The gate in `VoiceInputManager` is engine-agnostic: `boolean directSend = engine.isDirectToTerminal();` (no more `instanceof WhisperEngine`).

- **WhisperEngine**: direct-send maps to `WhisperConfig.streaming`, so text *also* appears progressively (token-by-token) during `TRANSCRIBING` — see streaming pipeline below.
- **ParakeetEngine**: the inference pipeline emits no per-token callbacks (only per-chunk `onProgress` for long audio — see "Parakeet Config, Chunking & ONNX Tuning"), so direct-send injects only the *final* text. A brief "Transcribing…" spinner shows during inference, then `setState(IDLE)` hides the overlay (`overlayRoot` → `GONE`) — no progressive token display, no phantom overlay.

`voice_auto_submit` (sub-toggle of direct-send) appends a single `\r` after the final text so the command executes itself — ideal hands-free in VR. It fires once in `onSuccess`, never on progressive deltas. The `\r` is posted with a **50ms delay** (`mainHandler.postDelayed`) so it arrives as a separate terminal read instead of being coalesced with the text into one chunk — a coalesced trailing `\r` is treated by TUIs (e.g. Claude Code) as a paste newline (Shift+Enter), not a submit. This mirrors `MacroExecutor`, which uses the same 50ms delay before its `\r`. Without auto-submit, the text lands at the prompt and the user presses Enter manually.

**Migration**: `whisper_streaming` was renamed to `voice_direct_send`. `SettingsDialog.isDirectSendEnabled()` migrates the old key on first read (idempotent). The native/JNI layer keeps the technical name `streaming` (`WhisperConfig.streaming`) — it genuinely controls progressive segment callbacks there. The Settings UI shows both toggles for **both** engines, with an orange warning when direct-send is on.

**Whisper progressive streaming pipeline** (active when direct-send is on for Whisper):
`whisper_full() → new_segment_callback → StreamCallbackData (C++) → JNI CallVoidMethod → WhisperBridge.onNativeSegment() → mainHandler.post → Callback.onPartialResult() → VoiceInputManager → terminal PTY (delta)`.

- `GetFloatArrayRegion` (copy) replaces `GetPrimitiveArrayCritical` (zero-copy) because the latter blocks the GC and forbids JNI callbacks
- `params.single_segment = false` enables multiple segments, triggering the callback progressively
- Benchmark (`DeviceProfiler`) always passes `streaming=false` to avoid callback overhead during profiling
- Default is OFF — behavior is 100% identical to pre-direct-send when disabled

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

The back key behavior is configurable via `SettingsDialog` (Spinner). Four modes persisted in `SharedPreferences` ("voidterm_settings" / "back_key_behavior"):
- **Escape** (default): `VoidTermTerminalViewClient.shouldBackButtonBeMappedToEscape()` returns `true`, TerminalView sends `\033`.
- **Toggle Keyboard**: `shouldBackButtonBeMappedToEscape()` returns `false`, `TermuxActivity.handleCustomBackKey()` toggles soft input.
- **Macro**: same dispatch path, executes a user-defined macro command (stored in "back_key_macro") via `MacroExecutor`. Supports full `{tag}` syntax.
- **Voice Input**: same dispatch path, calls `onVoiceToggle()` to start/stop voice recording (Push-to-Talk).

These four modes are the **single-tap** slot for the back key. Double/triple/long-press gestures layer on top via `KeyGestureDetector` — see "Key Gesture System" below.

### Volume Key Behavior

Volume Up and Volume Down are independently configurable via `SettingsActivity` (Spinner). Five modes each, persisted in `SharedPreferences` ("voidterm_settings" / "volume_up_behavior" and "volume_down_behavior"):
- **Default** (default): system volume control, key not intercepted.
- **Escape**: sends `\033` to the terminal session.
- **Toggle Keyboard**: toggles soft input.
- **Macro**: executes a user-defined macro command (stored in "volume_up_macro" / "volume_down_macro") via `MacroExecutor`. Supports full `{tag}` syntax.
- **Voice Input**: triggers `onVoiceToggle()`.

Handled entirely in `TermuxActivity.handleCustomVolumeKey(int keyCode)` — no `VoidTermTerminalViewClient` or `TerminalView` layer needed (unlike back key). These five modes are the **single-tap** slot per volume key; double/triple/long-press and the Vol+&Vol− combo layer on via `KeyGestureDetector` — see "Key Gesture System" below.

### Key Gesture System (multi-tap / long-press / combo)

`KeyGestureDetector` (`com.voidterm.input`) adds double-tap, triple-tap, long-press, and a Vol+&Vol− combo on top of the single-tap behaviors above. It is a pure **timed state machine**: it reads no `SharedPreferences` and never touches the terminal — `TermuxActivity` owns the policy (gesture → action), the detector owns the mechanism (recognize the gesture). Timing is driven by an injected `Scheduler` (`HandlerScheduler` on the main `Looper` in production; a virtual-time `FakeScheduler` in tests), so the whole machine is deterministically unit-tested (`KeyGestureDetectorTest`, 19 tests).

**Slots (15):** Vol+, Vol−, Back each have single/double/triple/long; the combo has single/double/triple (never long). Each slot maps to the existing action set (`default` / `escape` / `toggle_keyboard` / `macro` / `voice_input`). The 3 single-tap slots **reuse the existing keys** (`volume_up_behavior`, `volume_down_behavior`, `back_key_behavior` + their `_macro`) — zero migration. The 12 new slots use `gesture_<key>_<gesture>` keys (+ `_macro`); all constants live in `SettingsDialog`. UI is in `SettingsActivity`: an `addGestureRow(...)` factory (shared spinner+macro row, macro persisted live via a `TextWatcher`), double/triple/long behind a per-key "Advanced" expander, a "Combo (Vol+ & Vol-)" subsection, and a global "Gesture sensitivity" preset spinner.

**Consumption contract (safety-critical):** `onKeyDown`/`onKeyUp` return `true` (consumed) **iff the key is "intercepted"** — it has a double/triple/long armed, or (volume keys) the combo is armed. A key with only its single-tap configured is NOT intercepted, so the legacy `handleCustomBackKey`/`handleCustomVolumeKey` instant path runs (zero added latency — design "decision A"). The interception decision lives in the detector (the tested zone); `TermuxActivity.onKeyDown` just returns its boolean, ordered after `QuestInputHandler` and before the legacy handlers. **Back is safety-critical: when any Back gesture is armed the detector consumes ALL Back events and emits `\033` for the single-tap escape itself** — an un-consumed Back would reach `super.onKeyDown` and close the Activity.

**Timing presets** (`GestureTiming.fromPreset`, pref `gesture_timing_preset`): Reactif 200/400/50, Normal 280/500/60, Tolerant 400/700/90 ms (multi-tap window / long-press / combo window). Resolution: emit immediately when the armed max tap count is reached, else wait the multi-tap window; long-press fires once at threshold and suppresses the release tap; a combo-armed volume down opens a combo window — partner down within the window → combo (own single/double/triple counting), else the press is *promoted* to the individual key (its long/multi-tap still work).

**Volume emulation (option B):** when an intercepted volume key's resolved behavior is `default`, `TermuxActivity.onGestureResolved` calls `adjustVolume()` (`AudioManager.adjustStreamVolume`, `STREAM_MUSIC`, `FLAG_SHOW_UI`) so system volume is preserved alongside the added gestures.

**Dispatch & lifecycle:** the behavior switch shared by the legacy handlers and the gesture listener is extracted into `TermuxActivity.dispatchKeyAction(behavior, macroPrefKey)` (returns false on `default`/unknown so the caller decides). `refreshGestureConfig()` (onCreate + onResume) rebuilds the armed-set + timing from prefs; `gestureDetector.reset()` (onPause + onDestroy) cancels all pending timers.

### Lifecycle & Error Recovery

`TermuxActivity` implements `onPause()` to cancel active voice recording when backgrounded (prevents microphone leak). `onResume()` only rebuilds panels if the theme changed (tracked via `lastAppliedTheme` field) — avoids destroying/recreating panels on every resume. The uncaught exception handler chains to Android's default handler after logging.

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

All preference keys for `voidterm_settings` are centralized as `public static final` constants in `SettingsDialog` (e.g. `SettingsDialog.KEY_WHISPER_LANGUAGE`). Style prefs keys (`PREFS_NAME`, `KEY_FONT_SIZE`) are `public static final` in `TerminalStyleDialog`. Other classes (VoiceInputManager, DeviceProfiler, InterfaceTheme, VoidTermTerminalViewClient) reference these constants — never raw strings. This ensures the compiler catches key renames.

### Path Remapping (com.termux → com.voidterm)

Termux binaries have `/data/data/com.termux/files/usr` hardcoded in ELF .rodata. Three-layer remapping strategy:

1. **ELF patcher** (`TermuxBootstrapInstaller.patchTermuxPaths()`): Binary replacement `com.termux/files` → `com.voidterm/fil` (same-length, 16 chars). Symlink `fil → files` resolves paths. Also patches `com.termux/cache` → `com.voidterm/cac`. Text files get variable-length `com.termux` → `com.voidterm` replacement. Controlled by `PATCH_VERSION` (currently 11) — bump to force re-scan of all `$PREFIX` files.

2. **DPkg post-invoke hook** (`$PREFIX/lib/voidterm-patch-new.sh`): Registered via `$PREFIX/etc/apt/apt.conf.d/99-voidterm-patcher.conf`. Auto-patches ELF (via `perl -pi`) and text files (via `grep -I` + `sed -i`) after each `apt install`. Only processes `.list` files modified in last 2 minutes. **Critical**: apt config must reference `prefixDir` path (not `stagingDir`) — the staging dir is renamed after bootstrap install.

3. **LD_PRELOAD** (`libvoidterm-remap.so` / `voidterm_remap.c`): 14 file-access hooks (open, mkdir, stat, etc.) remap `/data/data/com.termux/` → `/data/data/com.voidterm/` at runtime. Function pointers resolved via `__attribute__((constructor))` at library load (thread-safe). Path buffer uses `PATH_MAX` (4096) to handle deep paths. Copied from APK native libs to `$PREFIX/lib/` by `TermuxActivity.copyRemapLibrary()`. Set in initial environment by `buildEnvironment()`. `.bashrc` snippet re-adds it after Termux profile overwrites `LD_PRELOAD`. Does NOT hook `execve()` — `libtermux-exec.so` bypasses PLT for exec, making our hook unreachable.

Build requirements for LD_PRELOAD: `extractNativeLibs=true` in AndroidManifest.xml, `useLegacyPackaging=true` in build.gradle (ensures .so files are extractable, not stored compressed in APK).

## Code Review & Remediation

`plans/CODE_REVIEW.md` documents 61 findings from a 5-agent parallel code review (concurrency, JNI, UI, architecture, config). Phases 1-5 completed (36 findings fixed). A second full-codebase review (5 parallel agents: concurrency, JNI, UI/lifecycle, security, voice pipeline) identified 7 critical + 16 important issues, all fixed in commit `94f7bae`. Key fixes: state machine precondition validation, JNI null guards, `__attribute__((constructor))` for LD_PRELOAD hooks, `darkenColor` clamping, `SessionListAdapter` tag-based position tracking, symlink path traversal defense, `onResume` theme gate.

## Code Style

- Java: 4 spaces, `camelCase` methods/variables, `PascalCase` classes
- JNI/C++: 4 spaces, follow `whisper_jni.cpp` conventions
- No unused imports or variables

## Branching

`main` (stable releases), `develop` (integration), `feature/*` (individual work). PRs target `develop`.
