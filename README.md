<p align="center">
  <img src="https://img.shields.io/badge/platform-Meta%20Quest-blue?style=for-the-badge&logo=meta" alt="Platform: Meta Quest" />
  <img src="https://img.shields.io/badge/arch-ARM64%20NEON-orange?style=for-the-badge" alt="Architecture: ARM64" />
  <img src="https://img.shields.io/badge/STT-whisper.cpp-green?style=for-the-badge" alt="STT: whisper.cpp" />
  <img src="https://img.shields.io/github/license/BaLaurent/voidTerm?style=for-the-badge" alt="License: GPL-3.0" />
  <img src="https://img.shields.io/github/v/release/BaLaurent/voidTerm?style=for-the-badge&label=version" alt="Version" />
</p>

<h1 align="center">VoidTerm</h1>

<p align="center">
  <strong>A full Linux terminal for Meta Quest with local voice input.</strong><br/>
  Speak your prompts. Code in VR. No cloud, no latency, no data sent.
</p>

<p align="center">
  <a href="#quick-start">Quick Start</a> &bull;
  <a href="#features">Features</a> &bull;
  <a href="#how-it-works">How It Works</a> &bull;
  <a href="#building-from-source">Build</a> &bull;
  <a href="#contributing">Contributing</a>
</p>

---

## What is VoidTerm?

VoidTerm is a [Termux](https://termux.dev) fork with integrated speech-to-text powered by [whisper.cpp](https://github.com/ggerganov/whisper.cpp). It runs entirely on-device — push a button, speak, and your words appear in the terminal.

Built for running [Claude Code](https://docs.anthropic.com/en/docs/claude-code) hands-free on Meta Quest.

## Features

**Voice Input**
- Push-to-Talk with Quest controller (A/X button) or on-screen mic button
- Local STT via whisper.cpp — fully offline, zero latency to cloud
- Transcription overlay with edit-before-send
- Streaming mode — text flows to the overlay in real-time as you speak
- Auto-tuning — benchmarks your device and picks optimal whisper settings
- Multi-language support with configurable whisper parameters

**Terminal**
- Full Termux terminal (bash, apt, Node.js, Python, Git, SSH...)
- Bluetooth keyboard support
- Configurable fonts (Fira Code, Hack, Inconsolata, Iosevka, JetBrains Mono)
- Color scheme customization
- 12 user-configurable macros with `{tag}` syntax for key combos

**Quest-Optimized UI**
- GameBoy-style touch control panel with D-pad, modifiers, and macros
- Compact toolbar mode (48dp bar with swipe macro pages)
- Fullscreen mode for maximum terminal space
- Large touch targets for VR raycast input
- Haptic feedback on button presses

## Quick Start

1. **Download** the latest APK from [Releases](https://github.com/BaLaurent/voidTerm/releases)

2. **Enable Developer Mode** on your Quest ([instructions](https://developer.oculus.com/documentation/native/android/mobile-device-setup/))

3. **Install via ADB:**
   ```bash
   adb install voidterm.apk
   ```

4. **Disable phantom process killing** (required for long-running processes like Claude Code):
   ```bash
   adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent"
   adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"
   adb shell settings put global settings_enable_monitor_phantom_procs false
   ```

5. **Launch** VoidTerm from Unknown Sources in your Quest app library

See [Quest Setup Guide](docs/QUEST_SETUP.md) for detailed configuration and [Claude Code Setup](docs/CLAUDE_CODE_SETUP.md) to get Claude running.

## How It Works

```
┌─────────────────────────────────────────────────────────────┐
│                        Meta Quest                           │
│                                                             │
│  Controller (A/X) ──► QuestInputHandler                     │
│                              │                              │
│                              ▼                              │
│  Microphone ──► AudioCapture ──► VoiceInputManager          │
│                 (16kHz PCM)       (state machine)           │
│                                       │                     │
│                                       ▼                     │
│                               whisper.cpp (JNI)             │
│                               ARM64 NEON / FP16             │
│                                       │                     │
│                                       ▼                     │
│                            TranscriptionOverlay             │
│                             [edit] [send] [cancel]          │
│                                       │                     │
│                                       ▼                     │
│                               Terminal PTY ──► bash         │
│                                                 └──► Claude │
└─────────────────────────────────────────────────────────────┘
```

The voice pipeline is a state machine: `IDLE → RECORDING → TRANSCRIBING → SHOWING_RESULT → EDITING → IDLE`. All processing is local — audio never leaves the device.

### Architecture

| Module | Role |
|--------|------|
| `:app` | Main application — voice pipeline, UI, Quest input handling |
| `:terminal-emulator` | Terminal emulation library (from Termux) |
| `:terminal-view` | Terminal rendering as an Android View (from Termux) |
| `:termux-shared` | Shared utilities (from Termux) |

### Native Layer

whisper.cpp is compiled via JNI into two ARM64 library variants:
- `whisper_jni` — baseline ARM NEON (all arm64 devices)
- `whisper_jni_v8fp16` — ARMv8.2-A half-precision (~15-30% faster on Quest XR2)

The optimal variant is selected at runtime based on device capabilities.

## Building from Source

### Prerequisites

| Tool | Version |
|------|---------|
| JDK | 17 |
| Android SDK | API 34 |
| Android NDK | r25+ (25.2.9519653) |
| CMake | 3.22.1+ |

### Build

```bash
git clone --recursive https://github.com/BaLaurent/voidTerm.git
cd voidTerm
./gradlew assembleDebug
```

The `--recursive` flag pulls the whisper.cpp submodule. See [Building from Source](docs/BUILDING.md) for signing, troubleshooting, and detailed instructions.

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for branch strategy, code style, and PR guidelines.

**Branch model:** `main` (stable) ← `develop` (integration) ← `feature/*` (work branches)

## Tech Stack

- **Terminal:** [Termux](https://termux.dev) (GPL-3.0)
- **Speech-to-Text:** [whisper.cpp](https://github.com/ggerganov/whisper.cpp) v1.7.3 (MIT) — ARM64 NEON optimized
- **Build:** Android NDK r25+, CMake 3.22.1+, Gradle 8.2
- **Target:** Meta Quest 2 / 3 / Pro (arm64-v8a only)

## License

[GPL-3.0](LICENSE)

VoidTerm is a fork of [Termux](https://termux.dev) (GPL-3.0). whisper.cpp is used under the [MIT license](https://github.com/ggerganov/whisper.cpp/blob/master/LICENSE).
