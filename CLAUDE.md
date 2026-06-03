# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project

VoidTerm is a Termux fork with integrated local voice input (whisper.cpp + Parakeet), targeting Meta Quest (ARM64 only). Users run Claude Code hands-free in VR via Push-to-Talk speech-to-text.

## Build Commands

```bash
./gradlew assembleDebug                  # Debug build
./gradlew assembleRelease                # Release build (unsigned)
./gradlew clean && ./gradlew assembleRelease
./gradlew testDebugUnitTest              # Unit tests (JUnit 4 + Mockito + Robolectric)
adb install app/build/outputs/apk/debug/app-debug.apk
```

- **Prerequisites:** JDK 17, Android SDK API 34, NDK r25+ (25.2.9519653), CMake 3.22.1+
- **JDK note:** if default JDK > 17, prefix with `JAVA_HOME=/usr/lib/jvm/java-17-openjdk`
- **Env:** `ANDROID_HOME`, `ANDROID_NDK_HOME`
- **Submodule:** `git submodule update --init --recursive` (whisper.cpp, pinned v1.7.3)
- Integration testing is manual on Quest devices.

## Architecture

Four Gradle modules: `:app` (main), `:terminal-emulator`, `:terminal-view`, `:termux-shared`. The last three are inherited from Termux and largely unmodified.

### Voice Pipeline (the core addition to Termux)

```
Quest Controller (A/X) → QuestInputHandler → VoiceInputManager (state machine)
Quest Microphone → AudioCapture (PCM float32 16kHz, VOICE_RECOGNITION source)
  → [optional] AudioPreprocessor (DC removal → HP → pre-emphasis → peak norm → gain)
  → engine (WhisperEngine via JNI | ParakeetEngine via ONNX) → TranscriptionOverlay
User confirms (Enter) → VoiceInputCallback.onVoiceTextReady() → Terminal PTY
```

**State machine** (`VoiceInputManager`): `IDLE → RECORDING → TRANSCRIBING → SHOWING_RESULT → EDITING → IDLE`. Double-tap cancels recording; Escape cancels transcription (also aborts native whisper via `WhisperBridge.abort()`); errors auto-dismiss after 3s. Min audio duration (0.5s) guards against ultra-short-PTT hallucinations. `reloadModel()` is gated on IDLE (avoids bridge-release races). `dispatchStateChange()` checks a `destroyed` flag to drop stale callbacks.

### Package Layout

| Package | Role |
|---|---|
| `com.voidterm.contracts` | Shared interfaces/DTOs: `VoiceState`, `VoiceInputCallback`, `TranscriptionListener`, `ControlPanel`, `ControlPanelListener`, `DownloadJob`, `FileSpec` |
| `com.voidterm.voice` | Voice system: `VoiceInputManager`, `TranscriptionEngine`/`WhisperEngine`/`ParakeetEngine`, `WhisperBridge`, `*Config`/`*Quantization`, `AudioCapture`/`AudioPreprocessor`/`AudioChunker`/`AudioFocus`, `DeviceProfiler`, `CpuInfo`, `HttpModelDownloader`, `*ModelCatalog`/`*ModelManager`, `TranscriptionOverlay` |
| `com.voidterm.input` | Controller & physical-key input: `QuestInputHandler`, `KeyGestureDetector`, `GestureTiming`, `Scheduler`/`HandlerScheduler` |
| `com.voidterm.app` | Activity/UI/services: `TermuxActivity`, `TerminalService`, `SessionManager`, `PanelController`, `GameBoyControlPanel`/`CompactPanel`/`CompactToolbar`/`PanelUtils`, `MacroExecutor`/`MacroStore`/`MacroEditDialog`, `SettingsActivity`/`SettingsDialog`, `InterfaceTheme`, `ModelDownloadService`/`DownloadJobs`/`*DownloadJob`, `*CatalogView`/`*QuantizationView`, `TermuxBootstrapInstaller` |

### Native build & JNI

`app/src/main/jni/whisper_jni.cpp` bridges to whisper.cpp. Six `WhisperBridge` native methods: `nativeInit`, `nativeTranscribe`, `nativeFree`, `nativeIsLoaded`, `nativeAbort` (cooperative cancel via ggml abort callback), `nativeGetSystemInfo`. Null guards on `language`/`audioData`. `g_abort_flag` is **process-global** — assumes a single active transcription, enforced Java-side by `isTranscribing` CAS.

Sources compile directly into one `.so` (matching the official whisper.android examples), bypassing the ggml CMake build. Two variants: `whisper_jni` (baseline ARM NEON) and `whisper_jni_v8fp16` (`-march=armv8.2-a+fp16`, ~15-30% faster on Quest XR2; selected at runtime when `SDK_INT >= 27`). **Gotchas:** `-O3` is always applied even in Debug (without it NEON matmul is ~15-20x slower); `GGML_USE_CPU` is required to register the CPU backend in `ggml-backend-reg.cpp`; OpenMP is not used (ggml has its own pthreads). `CpuInfo` picks thread count from `/sys/devices/system/cpu/` frequencies (drops lowest-freq cores for big.LITTLE), falling back to `(availableProcessors + 1) / 2`.

### Threading & thread-safety

Threads: main (UI/overlay/state), `AudioCapture-Thread` (record loop), `WhisperBridge-Transcribe` (inference), `WhisperBridge-ModelLoad` (one-time), `DeviceProfiler` (one-time benchmark). `VoiceInputManager` holds `stateLock` for transitions but **dispatches outside the lock**; preconditions (model loaded, audio start) are checked inside the lock before committing RECORDING. `WhisperBridge` uses `AtomicBoolean` guards to reject concurrent calls. `AudioCapture.stopRecording()` calls `audioRecord.stop()` *before* the thread join to unblock `READ_BLOCKING`.

## Voice config & models

**Engines.** `TranscriptionEngine` abstracts whisper vs Parakeet. `SettingsDialog.ENGINE_DEFAULT` (= `ENGINE_PARAKEET`) is the single source of truth for the default engine (Parakeet = better accuracy on Quest). Neither engine ships a bundled model (`assets/models/` is empty) — a model must always be provided (whisper: file picker or in-app catalog; Parakeet: in-app download), so switching the default carries no engine-specific regression.

**Model selection / download.** Whisper models come via `ACTION_OPEN_DOCUMENT` file picker (copied to `{filesDir}/models/`, name in `whisper_model_name`) or the `WhisperCatalogView` accordion. Parakeet comes via the `ParakeetQuantizationView` 2-row selector (int8 / fp32). Both download through one **generic foreground service** `ModelDownloadService` (ID 2, channel `voidterm_download`; separate from `TerminalService` ID 1 so `stopIfNoSessions()` can't kill it). `WhisperBridge.loadModel()` checks `{filesDir}/models/` first, then assets (currently none).

Download architecture (policy/mechanism split):
- `ModelDownloadService` = **mechanism**: foreground lifecycle, progress notification + Cancel action, `PARTIAL_WAKE_LOCK`, the generic skip/`.tmp`/atomic-rename loop. Reconstructs the job via `DownloadJobs.fromIntent` (extras `EXTRA_JOB_TYPE` + `EXTRA_MODEL_ID`). Cancel trips `cancelFlag` (checked between files and inside the byte loop via `InterruptedIOException`), deletes the partial `.tmp`, teardown silent. Resume skips completed files; an interrupted file restarts from scratch (no HTTP Range).
- `DownloadJob` (contract) = **policy**: `id()`, `displayName()`, `files()`→`List<FileSpec>`, `onComplete(Context)`. `ParakeetDownloadJob` (`JOB_TYPE="parakeet"`, `id()`=quantization) writes `KEY_PARAKEET_QUANTIZATION` + `KEY_MODEL_RELOAD_REQUESTED` and auto-activates. `WhisperDownloadJob` activates the model and sets `KEY_TRANSCRIPTION_ENGINE=whisper`.
- `HttpModelDownloader` = shared HuggingFace transfer (redirects, 200ms-throttled progress, `AtomicBoolean` cancel, atomic `.tmp` rename).
- UI observes private broadcasts `BROADCAST_PROGRESS/COMPLETE/ERROR` (`RECEIVER_NOT_EXPORTED`); `EXTRA_MODEL_ID` routes progress to the correct row. One download at a time (`ModelDownloadService.isRunning()`). Per-row delete: whisper via `WhisperModelManager.delete()`; Parakeet via `ParakeetModelManager.deleteModels()` (removes only that quantization's specific files — shared `nemo128.onnx`/`vocab.txt` always kept).
- Manifest: `WAKE_LOCK` permission + plain `<service>` (no `foregroundServiceType` — `targetSdk 28`).

**Whisper catalog** (`WhisperModelCatalog`): static registry of 31 ggml variants from `huggingface.co/ggerganov/whisper.cpp` (tiny/base/small/medium/large, `.en` + quantized; quantization non-uniform — q5_1 small, q5_0 large). `WhisperModelManager.nextActiveAfterDelete` falls the active model back to the first remaining one.

**Parakeet** (`ParakeetQuantization`, 2 entries; `ParakeetEngine` reads `KEY_PARAKEET_QUANTIZATION`, default `"int8"`):
- `INT8` (~670 MB) — weights inline in the encoder `.onnx`.
- `FP32` (~2.55 GB) — encoder weights in a separate `encoder-model.onnx.data` (2.44 GB) resolved automatically by ONNX Runtime. The fp32 row shows an **adaptive RAM note** (`applyFp32Warning`) read from `MemoryInfo.totalMem`: <4 GB not recommended / <8 GB caution / ≥8 GB sufficient — no hardcoded Quest figure.

`nemo128.onnx` + `vocab.txt` are shared across quantizations. ONNX session tuning in `loadModel`: `setOptimizationLevel(ALL_OPT)` + `setIntraOpNumThreads` (only these two knobs are real wins; changing threads sets `KEY_MODEL_RELOAD_REQUESTED`). Startup logs `OrtEnvironment.getAvailableProviders()` to weigh a future XNNPACK toggle (NNAPI not pursued without on-device proof — see `plans/`).

**Chunking** (`AudioChunker`, pure/stateless, `AudioChunkerTest`): audio that fits one encoder pass is a single chunk referencing the original array — **byte-for-byte identical to the pre-chunking path**. Longer audio splits at silence (lowest-RMS frame below `silenceThreshold` in the trailing `searchBand`); continuous speech hard-splits with an `overlap` region flagged `isFallbackSplit`. An undersized trailing chunk (<0.5s) merges into the previous one (keeps the min-duration guard honest). `ParakeetEngine.runChunked()` runs the full pipeline per chunk (decoder reset per chunk is correct — silence is a natural reset), checks `abortFlag` between chunks, and merges with `dedupLeading()` (word-level, case-insensitive). No progressive display (final merged text only). `AudioCapture` accumulates 100ms reads into a `List<float[]>`, capped only at a 2-minute safety ceiling (whisper.cpp self-windows; Parakeet's chunk window is separate).

**`ParakeetConfig`** (immutable, `DEFAULT` = single source of defaults; mirrors `WhisperConfig`) is cached `volatile` in `ParakeetEngine`, invalidated by an `OnSharedPreferenceChangeListener` on `CONFIG_KEYS`. Keys are `parakeet_*` in `SettingsDialog`; UI is "Parakeet Advanced..." (shown only when Parakeet is selected). `maxWindowSamples` is **clamped 10–30s** — raising it past the tested ceiling re-introduces the OOM chunking prevents.

### Transcription settings (whisper)

Read fresh per transcription via `VoiceInputManager.buildWhisperConfig()` → `WhisperConfig`. Flattened to JNI primitives (avoids `GetFieldID` boilerplate). Advanced ones hidden behind a collapsible "Advanced..." button.

| Key | Type | Default | Description |
|---|---|---|---|
| `whisper_language` | String | `"en"` | ISO 639-1 or `"auto"` |
| `whisper_translate` | boolean | `false` | Translate to English |
| `whisper_initial_prompt` | String | `""` | Vocabulary hints |
| `whisper_temperature` | float | `0.0f` | 0=precise, 1=creative |
| `whisper_beam_search` | boolean | `false` | Beam vs greedy |
| `whisper_beam_size` | int | `5` | Beam width 2-8 |
| `whisper_thread_override` | int | `0` | 0=auto via `CpuInfo` |
| `whisper_suppress_non_speech` | boolean | `false` | Filter non-speech tokens |
| `voice_direct_send` | boolean | `false` | Bypass review overlay (engine-agnostic) |
| `voice_auto_submit` | boolean | `false` | Press Enter after final text (direct-send only) |

### Audio preprocessing (`AudioConfig`, `DEFAULT` = single source of defaults)

| Key | Type | Default | Description |
|---|---|---|---|
| `audio_gain` | float | `1.0f` | Output gain |
| `audio_pre_emphasis` | float | `0.97f` | 0=off, 0.97=standard speech |
| `audio_hp_cutoff` | int | `80` | High-pass cutoff (Hz); `hpAlpha()` derives the IIR coeff |
| `audio_norm_target` | float | `0.9f` | Peak normalization target |

Pipeline order: DC removal → HP → pre-emphasis → peak normalization → output gain. **Gain is intentionally AFTER normalization** — before, normalization would undo non-clipping gain and only clipping distortion would remain. `AudioDebugDialog` does real-time A/B testing; `processWithDiagnostics()` returns per-stage stats. Cached `volatile` in `VoiceInputManager.cachedAudioConfig`.

### Audio focus

`AudioFocus` (owned by `AudioCapture`) pauses other apps' media while voice is captured. It requests `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` (`USAGE_ASSISTANT` / `CONTENT_TYPE_SPEECH`): peer media apps **pause** (not duck) and the system suppresses notification sounds; the "transient" hint tells them to resume on abandon. `acquire()`/`abandon()` are `synchronized` + **idempotent** (a duplicate acquire and an abandon-with-nothing-held are both no-ops). `acquire()` runs in `startRecording()`; `abandon()` runs in `stopRecording()` and again on release. The idempotency matters because stop is reached from several paths (PTT release, cancel, background, error recovery) — the only fatal failure is a **missed abandon** (music paused forever), which the balanced acquire/abandon on the single start/stop pair prevents.

### Direct send & auto-submit

Direct-send is an **engine capability** (`TranscriptionEngine.isDirectToTerminal()`, no `instanceof`): the gate is `boolean directSend = engine.isDirectToTerminal();`. Whisper maps it to `WhisperConfig.streaming` (token-by-token progressive display during TRANSCRIBING); Parakeet emits no per-token callbacks so it injects only the final text (brief "Transcribing…" spinner, then overlay GONE).

`voice_auto_submit` appends one `\r` after the final text (fires once in `onSuccess`, never on deltas). **The `\r` is posted with a 50ms delay** so it lands as a separate terminal read — a coalesced trailing `\r` is treated by TUIs (e.g. Claude Code) as a paste newline (Shift+Enter), not submit. (Mirrors `MacroExecutor`'s 50ms delay.)

Migration: `whisper_streaming` → `voice_direct_send` (`SettingsDialog.isDirectSendEnabled()` migrates on first read). The JNI keeps the name `streaming` (it genuinely controls progressive segment callbacks). Whisper streaming path: `whisper_full() → new_segment_callback → JNI CallVoidMethod → WhisperBridge.onNativeSegment() → mainHandler.post → Callback.onPartialResult() → PTY delta`. Uses `GetFloatArrayRegion` (copy) not `GetPrimitiveArrayCritical` — the latter blocks the GC and forbids JNI callbacks. `params.single_segment = false` enables the progressive callback; the benchmark always passes `streaming=false`. Default OFF = behavior identical to pre-direct-send.

### Auto-tuning (`DeviceProfiler`, stateless)

Runs a 1s synthetic benchmark (440Hz sine, greedy) once per model, classifies a tier, writes optimal defaults. Cached in `autotune_model`/`autotune_benchmark_ms`/`autotune_tier`. User changes are tracked in `user_overrides` (StringSet) and never overwritten; "Reset to Auto" clears overrides and re-profiles.

| Tier | 1s benchmark | beam_search | beam_size | proportional_context | suppress_non_speech |
|---|---|---|---|---|---|
| FAST | < 600ms | true | 5 | true | true |
| MEDIUM | 600-1200ms | true | 3 | true | true |
| SLOW | > 1200ms | false | -- | true | true |

## UI & Input

`VoiceInputCallback` (PTY injection) and `TranscriptionListener` (overlay actions) are implemented by `TermuxActivity` and `VoiceInputManager` respectively. Changes to `com.voidterm.contracts` ripple through the whole pipeline.

### Context menu & terminal style

`TermuxActivity` registers `TerminalView` for context menus; the selection "More" button → `showContextMenu()` (Paste, Share, Select URL, Style, Reset, Toggle keyboard). `TerminalStyleDialog` manages font size/family/colors (prefs "voidterm_style"; `PREFS_NAME`/`KEY_FONT_SIZE` constants live there). Colors write directly to `mEmulator.mColors.mCurrentColors[]` indices 256 (fg) / 257 (bg) / 258 (cursor); restored on session start via `applySavedStyle()`. Bundled fonts in `assets/fonts/`.

### Control panels

Three panels, all implementing `ControlPanel` (contracts) and talking to `TermuxActivity` via `ControlPanelListener` (`onSendToTerminal`, `onVoiceToggle`, `onSettingsRequested`):
- `GameBoyControlPanel` — Game-Boy-styled, D-pad + modifiers + macros + burger (☰). Key codes: Enter→`\r` (submit), S-Enter→`\n` (newline), TAB→`\t` (SHF-aware), S-TAB→`\033[Z`.
- `CompactPanel` — 170dp, 4×6, all 12 macros at once (no paging). No Shift button (`isShiftActive()`=false; S-TAB/S-ENT cover shift uses).
- `CompactToolbar` — 48dp bar above the keyboard (or permanent in fullscreen); swipe for 3 macro pages of 4.

`panel_mode` (`KEY_PANEL_MODE`): `"gameboy"` (default) / `"compact"` / `"fullscreen"`. Visibility is centralized in **`PanelController.updateVisibility()`** (tracks `activePanel`, syncs modifier + macro-page state across transitions). `VoidTermTerminalViewClient.readControlKey()`/`readShiftKey()` delegate to `PanelController.consumeCtrl()`/`consumeShift()` for O(1) modifier consumption. `CompactPanel`/`CompactToolbar` share factories in `PanelUtils`; GameBoy keeps its own shapes. Panels register prefs listeners in `onAttachedToWindow()`, unregister in `onDetachedFromWindow()`; arrow repeat uses tracked `activeRepeatRunnable`/`activeRepeatView` cancelled on touch-up and detach.

### Macros

12 macros (3 pages × 4) edited via long-press → `MacroEditDialog`, persisted in `MacroStore` (prefs "voidterm_macros", JSON of 12; migrates from the old 4-macro format by appending 8 defaults). `MacroExecutor`: plain text (no `{`) sends text + `\r`; otherwise parses `{tag}` → escape sequences. Tags: `{esc}` `{enter}` `{tab}` `{up}` `{down}` `{left}` `{right}` `{home}` `{end}` `{f1}`-`{f12}` `{ctrl+a}`-`{ctrl+z}` `{shift+KEY}` `{alt+KEY}` `{wait:N}`; `{{`→`{`, `}}`→`}`.

### Theming

`InterfaceTheme` enum: 4 themes (GAMEBOY, DARK_GAMEBOY, ATOMIC_PURPLE, HACKERBOY), each 7 panel colors + `drawerBg`/`drawerAccent`. Static helpers `darkenColor()` (clamps ≥0), `lightenColor()` (clamps ≤255), `isLightColor()` (luminance) drive adaptive text. Persisted via `KEY_THEME`. The drawer (`buildDrawerPanel()`/`SessionListAdapter`) and `SettingsActivity` (`computeDerivedColors()` from `theme.background`) adapt to the theme; theme change rebuilds the drawer / `recreate()`s the Activity.

### Back / Volume / Gesture keys

Single-tap behavior per key is a Spinner-selected mode; double/triple/long-press and the Vol+&Vol− combo layer on via `KeyGestureDetector`.

| Key | Modes (single-tap) | Single-tap pref | Handler |
|---|---|---|---|
| Back | escape (default) / toggle_keyboard / macro / voice_input | `back_key_behavior` (+`back_key_macro`) | `VoidTermTerminalViewClient` (`shouldBackButtonBeMappedToEscape`) + `TermuxActivity.handleCustomBackKey()` |
| Vol+ | default / escape / toggle_keyboard / macro / voice_input | `volume_up_behavior` (+`_macro`) | `TermuxActivity.handleCustomVolumeKey()` |
| Vol− | (same five) | `volume_down_behavior` (+`_macro`) | `TermuxActivity.handleCustomVolumeKey()` |

The shared behavior switch is `TermuxActivity.dispatchKeyAction(behavior, macroPrefKey)` (returns false on `default`/unknown so the caller decides).

`KeyGestureDetector` (`com.voidterm.input`) is a pure **timed state machine** — reads no prefs, never touches the terminal (policy lives in `TermuxActivity`, mechanism in the detector); timing comes from an injected `Scheduler` (`HandlerScheduler` prod / `FakeScheduler` tests), so it's deterministically unit-tested (`KeyGestureDetectorTest`, 19 tests). 15 slots: Vol+/Vol−/Back each single/double/triple/long, combo single/double/triple (never long). The 3 single-tap slots reuse the existing prefs above (zero migration); the 12 new ones use `gesture_<key>_<gesture>` (+`_macro`). UI: `SettingsActivity.addGestureRow(...)` factory + per-key "Advanced" expander + combo subsection + global `gesture_timing_preset` spinner.

- **Consumption contract (safety-critical):** `onKeyDown`/`onKeyUp` return `true` (consumed) **iff the key is intercepted** (has a double/triple/long armed, or — volume — the combo). A key with only single-tap is NOT intercepted, so the legacy instant path runs (zero added latency). **Back is safety-critical: when any Back gesture is armed the detector consumes ALL Back events and emits `\033` itself** — an un-consumed Back reaches `super.onKeyDown` and closes the Activity.
- **Timing presets** (`GestureTiming.fromPreset`): Reactif 200/400/120, Normal 280/500/180, Tolerant 400/700/280 ms (multi-tap window / long-press / combo window). A combo-armed Vol− opens a combo window: partner down within it → combo, else the press is promoted to the individual key.
- **Volume emulation:** an intercepted volume key resolving to `default` calls `adjustVolume()` (`AudioManager.adjustStreamVolume`, `STREAM_MUSIC`, `FLAG_SHOW_UI`) so system volume still works.
- Lifecycle: `refreshGestureConfig()` rebuilds armed-set + timing (onCreate + onResume); `gestureDetector.reset()` cancels pending timers (onPause + onDestroy).

### Multi-session (left drawer)

`SessionManager` holds a `List<TerminalSession>` + current index (`createSession`/`switchToSession`/`removeSession`; auto-creates one when the last is removed; `SessionChangeListener` notifies `TermuxActivity`). UI is a `DrawerLayout` wrapping `rootLayout`: 280dp `Gravity.START` panel with a `ListView` + `SessionListAdapter` (active dot + monospace name + close). `TermuxTerminalSessionClient` guards `onTextChanged`/`onColorsChanged` to the active session only; `onSessionFinished` → `sessionManager.removeSession()`. Voice/panels/macros all go through `getCurrentSession()`.

### Other interface settings (in `SettingsDialog`, prefs "voidterm_settings")

- **Haptics** `haptic_feedback` (default true) — cached `volatile hapticEnabled` in `GameBoyControlPanel`/`CompactToolbar`, invalidated by a prefs listener; static `isHapticEnabled()` exists but not for hot paths.
- **Tap-to-toggle keyboard** `tap_toggle_keyboard` (default true) — `VoidTermTerminalViewClient.onSingleTapUp()`.
- **Panel customize** "Customize Layout" enabled only in GameBoy mode; the toolbar checkbox is forced on in fullscreen.

## Conventions & invariants

- **SharedPreferences caching:** hot-path readers (`VoidTermTerminalViewClient`, panels, `VoiceInputManager`, `ParakeetEngine`) cache values in `volatile` fields and invalidate via `OnSharedPreferenceChangeListener` — never read prefs per event.
- **Key constants:** all `voidterm_settings` keys are `public static final` in `SettingsDialog` (e.g. `SettingsDialog.KEY_WHISPER_LANGUAGE`); style keys in `TerminalStyleDialog`. Never use raw strings (compiler catches renames).
- **Single sources of truth:** `ENGINE_DEFAULT`, `AudioConfig.DEFAULT`, `ParakeetConfig.DEFAULT`, `WhisperConfig`.
- **Lifecycle/error recovery:** `onPause()` cancels active recording (mic-leak guard); `onResume()` only rebuilds panels when the theme changed (`lastAppliedTheme`). The `VoiceInput-Pipeline` thread wraps its body in try-catch → ERROR (never stuck in TRANSCRIBING). `WhisperBridge.release()` guards `nativeFree()` behind `thread.isAlive()`. Bootstrap callbacks and `dispatchStateChange` bail on `isDestroyed()`; `onDestroy()` calls `viewClient.release()`.

## Path Remapping (com.termux → com.voidterm)

Termux binaries have `/data/data/com.termux/files/usr` hardcoded in ELF `.rodata`. Three layers:

1. **ELF patcher** (`TermuxBootstrapInstaller.patchTermuxPaths()`): same-length binary replacement `com.termux/files` → `com.voidterm/fil` (16 chars), resolved by a `fil → files` symlink; also `com.termux/cache` → `com.voidterm/cac`. Text files get variable-length `com.termux` → `com.voidterm`. Controlled by `PATCH_VERSION` (currently 11) — bump to force re-scan of all `$PREFIX` files.
2. **DPkg post-invoke hook** (`$PREFIX/lib/voidterm-patch-new.sh`, registered via `apt.conf.d/99-voidterm-patcher.conf`): auto-patches ELF (`perl -pi`) + text (`grep -I` + `sed -i`) after each `apt install`; only `.list` files modified in the last 2 minutes. **Critical:** the apt config must reference `prefixDir` (not `stagingDir`) — staging is renamed after bootstrap install.
3. **LD_PRELOAD** (`libvoidterm-remap.so` / `voidterm_remap.c`): 14 file-access hooks (open, mkdir, stat, …) remap `/data/data/com.termux/` → `/data/data/com.voidterm/` at runtime; function pointers resolved via `__attribute__((constructor))` (thread-safe); `PATH_MAX` (4096) buffers. Copied to `$PREFIX/lib/` by `copyRemapLibrary()`, set in the initial env by `buildEnvironment()`, re-added by a `.bashrc` snippet after the Termux profile overwrites `LD_PRELOAD`. **Does NOT hook `execve()`** — `libtermux-exec.so` bypasses the PLT for exec.

Build requirements for LD_PRELOAD extraction: `extractNativeLibs=true` (AndroidManifest) + `useLegacyPackaging=true` (build.gradle) so `.so` files are extractable, not stored compressed.

## Code style & branching

- Java: 4 spaces, `camelCase` members, `PascalCase` classes. JNI/C++: 4 spaces, follow `whisper_jni.cpp`. No unused imports/variables.
- Branches: `main` (releases), `develop` (integration), `feature/*`. PRs target `develop`.
- Code review history (2 parallel multi-agent passes, findings fixed) lives in `plans/CODE_REVIEW.md` and `plans/completed/`.
