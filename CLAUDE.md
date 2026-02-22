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
```

**Prerequisites:** JDK 17, Android SDK API 34, NDK r25+ (25.2.9519653), CMake 3.22.1+

**JDK note:** If default JDK is newer than 17, prefix builds with `JAVA_HOME=/usr/lib/jvm/java-17-openjdk`

**Environment variables:** `ANDROID_HOME`, `ANDROID_NDK_HOME`

**Submodule:** whisper.cpp must be initialized: `git submodule update --init --recursive`

No automated test suite. Testing is manual on Quest devices.

## Architecture

Four Gradle modules: `:app` (main), `:terminal-emulator`, `:terminal-view`, `:termux-shared`. The last three are inherited from Termux and largely unmodified.

### Voice Pipeline (the core addition to Termux)

```
Quest Controller (A/X) → QuestInputHandler → VoiceInputManager (state machine)
Quest Microphone → AudioCapture (PCM float32 16kHz) → WhisperBridge (JNI) → TranscriptionOverlay
User confirms (Enter) → VoiceInputCallback.onVoiceTextReady() → Terminal PTY
```

**State machine** (VoiceInputManager): `IDLE → RECORDING → TRANSCRIBING → SHOWING_RESULT → EDITING → IDLE`. Double-tap cancels recording. Escape cancels transcription. Error auto-dismisses after 3s.

### Package Layout

| Package | Role |
|---|---|
| `com.voidterm.contracts` | Shared interfaces: `VoiceState`, `VoiceInputCallback`, `TranscriptionListener` |
| `com.voidterm.voice` | Voice system: `VoiceInputManager`, `AudioCapture`, `WhisperBridge`, `TranscriptionOverlay` |
| `com.voidterm.input` | Controller mapping: `QuestInputHandler` |
| `com.voidterm.app` | Activity, UI, styling: `TermuxActivity`, `GameBoyControlPanel`, `CompactToolbar`, `MacroExecutor`, `MacroEditDialog`, `TerminalStyleDialog`, `SettingsDialog`, `ExtraKeysConfig` |

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

`CompactToolbar` is a 48dp horizontal bar shown above the soft keyboard (or permanently in fullscreen mode). Main row: ESC, CTL, SHF, TAB, arrows (◀▲▼▶), Enter (↵), STT (🎤), burger menu (☰). Swipe left for macro pages (3 pages of 4 buttons). Same `ControlPanelListener` interface as `GameBoyControlPanel`.

### Macro System

12 user-configurable macros displayed as 3 pages of 4 buttons in both `GameBoyControlPanel` (page cycle button above vertical stack) and `CompactToolbar` (page indicator button + swipe navigation across pages). Edited via long-press → `MacroEditDialog`. Persistence centralized in `MacroStore` (SharedPreferences "voidterm_macros", JSON array of 12 objects). Migrates automatically from the old 4-macro format by preserving existing macros and appending 8 defaults.

`MacroExecutor` parses and executes macro commands. Two modes:
- **Plain text** (no `{`): sends text + `\r` (backward compatible)
- **Key combinations** (contains `{`): parses `{tag}` syntax into terminal escape sequences

Supported tags: `{esc}`, `{enter}`, `{tab}`, `{up}`, `{down}`, `{left}`, `{right}`, `{home}`, `{end}`, `{f1}`-`{f12}`, `{ctrl+a}`-`{ctrl+z}`, `{shift+KEY}`, `{alt+KEY}`, `{wait:N}` (delay ms). Escaped braces: `{{` → `{`, `}}` → `}`.

### Settings & Model Selection

`SettingsDialog` (AlertDialog in ScrollView, programmatic layout like `TerminalStyleDialog`) lets users select a custom whisper.cpp model file via Android's `ACTION_OPEN_DOCUMENT` file picker. The selected file is copied to `{filesDir}/models/`, its name persisted in `SharedPreferences` ("voidterm_settings" / "whisper_model_name"), and hot-reloaded via `VoiceInputManager.reloadModel()`. Default model: `ggml-base.bin` (bundled in assets). `WhisperBridge.loadModel()` checks `{filesDir}/models/` first, falls back to assets, and returns a clear error if neither exists. GPU toggle (default off) controls `whisper_context_params.use_gpu`. `WhisperBridge` selects the FP16 library variant at runtime if `Build.VERSION.SDK_INT >= 27` (ARMv8.2-A support).

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

Data flow: `SharedPreferences → WhisperConfig → WhisperBridge.transcribe() → nativeTranscribe() JNI → whisper_full_params`. The JNI layer receives flattened primitives (not the config object) to avoid `GetFieldID` boilerplate. Advanced settings (temperature, beam search, threads, suppress) are hidden behind a collapsible "Advanced..." button in the UI.

### Auto-Tuning (DeviceProfiler)

`DeviceProfiler` (stateless utility in `com.voidterm.voice`) auto-tunes transcription parameters after model load. Runs a 1s synthetic audio benchmark (440Hz sine, greedy decode) once per model, classifies the device into a performance tier, and writes optimal defaults.

| Tier | Benchmark (1s audio) | beam_search | beam_size | proportional_context | suppress_non_speech |
|---|---|---|---|---|---|
| FAST | < 600ms | true | 5 | true | true |
| MEDIUM | 600-1200ms | true | 3 | true | true |
| SLOW | > 1200ms | false | -- | true | true |

Results cached in SharedPreferences (`autotune_model`, `autotune_benchmark_ms`, `autotune_tier`). User manual changes tracked in `user_overrides` (StringSet) — auto-tuning never overwrites overridden keys. "Reset to Auto" button in SettingsDialog clears overrides and forces re-profiling. One-time migration detects pre-existing parameter changes and marks them as user overrides.

### Haptic Feedback

Haptic feedback on button presses is toggled via checkbox in `SettingsDialog` Interface section. Persisted in `SharedPreferences` ("voidterm_settings" / "haptic_feedback", default `true`). Checked by `SettingsDialog.isHapticEnabled(Context)` in `GameBoyControlPanel`, `CompactToolbar`, and directly via `SharedPreferences` in `TerminalView`.

### Fullscreen Mode

Fullscreen mode hides the GameBoy control panel entirely and shows only the CompactToolbar (48dp), regardless of keyboard state. Toggled via "Fullscreen (toolbar only)" checkbox in `SettingsDialog` Interface section. Persisted in `SharedPreferences` ("voidterm_settings" / "fullscreen_mode", default `false`). When enabled, the "Compact toolbar above keyboard" checkbox is disabled (toolbar is forced on). Panel visibility is centralized in `TermuxActivity.updatePanelVisibility()`, called from keyboard listener, settings changes, bootstrap completion, and initial layout. `VoidTermTerminalViewClient.readControlKey()`/`readShiftKey()` check actual toolbar visibility (not keyboard state) to correctly read modifier keys in both modes.

### Tap-to-Toggle Keyboard

Tapping the terminal view toggles the soft keyboard via `VoidTermTerminalViewClient.onSingleTapUp()`. This behavior is controlled by `SharedPreferences` ("voidterm_settings" / "tap_toggle_keyboard", default `true`). Configurable via checkbox in `SettingsDialog` Interface section.

### Back Key Behavior

The back key behavior is configurable via `SettingsDialog` (Spinner). Three modes persisted in `SharedPreferences` ("voidterm_settings" / "back_key_behavior"):
- **Escape** (default): `VoidTermTerminalViewClient.shouldBackButtonBeMappedToEscape()` returns `true`, TerminalView sends `\033`.
- **Toggle Keyboard**: `shouldBackButtonBeMappedToEscape()` returns `false`, `TermuxActivity.handleCustomBackKey()` toggles soft input.
- **Macro**: same dispatch path, executes a user-defined macro command (stored in "back_key_macro") via `MacroExecutor`. Supports full `{tag}` syntax.

## Implementation Plan

`plans/IMPLEMENTATION_PLAN.md` defines 6 phases with strict file ownership per agent. Phases 1-3 (scaffolding, components, core systems) produce independent modules. Phase 4 (integration) wires everything into TermuxActivity. Phases 5-6 are docs and validation. Current status: Phases 1-4 implemented.

## Code Style

- Java: 4 spaces, `camelCase` methods/variables, `PascalCase` classes
- JNI/C++: 4 spaces, follow `whisper_jni.cpp` conventions
- No unused imports or variables

## Branching

`main` (stable releases), `develop` (integration), `feature/*` (individual work). PRs target `develop`.
