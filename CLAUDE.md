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

`app/src/main/jni/whisper_jni.cpp` bridges to whisper.cpp (git submodule at `app/src/main/jni/whisper.cpp`, pinned to v1.7.3). Four native methods on `WhisperBridge`: `nativeInit`, `nativeTranscribe`, `nativeFree`, `nativeIsLoaded`.

### Threading Model

- **Main thread:** UI, overlay, state dispatch
- **AudioCapture-Thread:** AudioRecord read loop (active only during recording)
- **WhisperBridge-Transcribe:** whisper.cpp inference (burst 2-4s)
- **WhisperBridge-ModelLoad:** one-time model loading from assets

Thread safety: `VoiceInputManager` uses `stateLock` for state transitions, dispatches outside lock. `WhisperBridge` uses `AtomicBoolean` guards for concurrent call rejection. `AudioCapture` uses `lock` for start/stop.

## Key Contracts

Changes to files in `com.voidterm.contracts` affect the entire voice pipeline. `VoiceInputCallback` is implemented by TermuxActivity (injects text into PTY). `TranscriptionListener` is implemented by `VoiceInputManager` (receives overlay user actions).

### Context Menu & Style System

`TermuxActivity` registers `TerminalView` for context menus (`registerForContextMenu`). The "More" button in text selection triggers `showContextMenu()` which is handled by `onCreateContextMenu`/`onContextItemSelected` in the Activity. Options: Paste, Share, Select URL, Style, Reset, Toggle keyboard.

`TerminalStyleDialog` manages terminal appearance (font size, font family, color scheme). Preferences persist via `SharedPreferences` ("voidterm_style"). Bundled fonts (TTF) are in `app/src/main/assets/fonts/`. Colors are applied directly to `mEmulator.mColors.mCurrentColors[]` indices 256 (fg), 257 (bg), 258 (cursor). Saved styles are restored on session start via `applySavedStyle()`.

### GameBoy Control Panel

`GameBoyControlPanel` provides a touchscreen control panel styled like a Game Boy. Includes D-pad, modifier keys (Ctrl, Shift, Esc), Tab/S-Tab, Enter/S-Enter, macro buttons, and a burger menu button (☰). Communicates with `TermuxActivity` via `ControlPanelListener` interface (`onSendToTerminal`, `onVoiceToggle`, `onSettingsRequested`). Key codes: Enter sends `\r` (submit), S-Enter sends `\n` (newline without submit), TAB sends `\t` (respects SHF state for backtab), S-TAB sends `\033[Z` (backtab).

### Macro System

12 user-configurable macros displayed as 3 pages of 4 buttons in both `GameBoyControlPanel` (page cycle button above vertical stack) and `CompactToolbar` (page indicator button + swipe navigation across pages). Edited via long-press → `MacroEditDialog`. Persistence centralized in `MacroStore` (SharedPreferences "voidterm_macros", JSON array of 12 objects). Migrates automatically from the old 4-macro format by preserving existing macros and appending 8 defaults.

`MacroExecutor` parses and executes macro commands. Two modes:
- **Plain text** (no `{`): sends text + `\r` (backward compatible)
- **Key combinations** (contains `{`): parses `{tag}` syntax into terminal escape sequences

Supported tags: `{esc}`, `{enter}`, `{tab}`, `{up}`, `{down}`, `{left}`, `{right}`, `{home}`, `{end}`, `{f1}`-`{f12}`, `{ctrl+a}`-`{ctrl+z}`, `{shift+KEY}`, `{alt+KEY}`, `{wait:N}` (delay ms). Escaped braces: `{{` → `{`, `}}` → `}`.

### Settings & Model Selection

`SettingsDialog` (AlertDialog, programmatic layout like `TerminalStyleDialog`) lets users select a custom whisper.cpp model file via Android's `ACTION_OPEN_DOCUMENT` file picker. The selected file is copied to `{filesDir}/models/`, its name persisted in `SharedPreferences` ("voidterm_settings" / "whisper_model_name"), and hot-reloaded via `VoiceInputManager.reloadModel()`. Default model: `ggml-base.bin` (bundled in assets). `WhisperBridge.loadModel()` checks `{filesDir}/models/` first, falls back to assets, and returns a clear error if neither exists.

### Haptic Feedback

Haptic feedback on button presses is toggled via checkbox in `SettingsDialog` Interface section. Persisted in `SharedPreferences` ("voidterm_settings" / "haptic_feedback", default `true`). Checked by `SettingsDialog.isHapticEnabled(Context)` in `GameBoyControlPanel`, `CompactToolbar`, and directly via `SharedPreferences` in `TerminalView`.

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
