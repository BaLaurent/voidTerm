<p align="center">
  <img src="https://img.shields.io/badge/platform-Android%209%2B-3DDC84?style=for-the-badge&logo=android" alt="Platform: Android 9+" />
  <img src="https://img.shields.io/badge/arch-ARM64%20NEON-orange?style=for-the-badge" alt="Architecture: ARM64" />
  <img src="https://img.shields.io/badge/STT-whisper.cpp%20%7C%20Parakeet-green?style=for-the-badge" alt="STT: whisper.cpp | Parakeet" />
  <img src="https://img.shields.io/github/license/BaLaurent/voidTerm?style=for-the-badge" alt="License: GPL-3.0" />
  <img src="https://img.shields.io/github/v/release/BaLaurent/voidTerm?style=for-the-badge&label=version" alt="Version" />
</p>

<h1 align="center">VoidTerm</h1>

<p align="center">
  <strong>A full Linux terminal for Android with local voice input.</strong><br/>
  Speak your prompts. Code hands-free — on a phone, a tablet, or in VR. No cloud, no latency, no data sent.
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

VoidTerm is a [Termux](https://termux.dev) fork with integrated, fully on-device speech-to-text. Push a button, speak, and your words appear in the terminal — push to talk, no cloud, nothing leaves the device.

It runs on **any modern Android device** (Android 9+, arm64) — phones, tablets, and VR headsets like the Meta Quest. Originally built for running [Claude Code](https://docs.anthropic.com/en/docs/claude-code) hands-free on Quest, it works just as well on a regular Android phone or tablet.

Two interchangeable local STT engines are bundled: [whisper.cpp](https://github.com/ggerganov/whisper.cpp) and [NVIDIA Parakeet TDT v3](https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx) — pick whichever fits your accuracy/speed needs in Settings.

## Features

**Voice Input**
- Push-to-Talk with on-screen mic button (or Quest controller A/X button on headsets)
- Two local STT engines — whisper.cpp and NVIDIA Parakeet TDT v3, both fully offline with zero latency to cloud
- Transcription overlay with edit-before-send
- Streaming mode — text flows to the overlay in real-time as you speak
- Auto-tuning — benchmarks your device and picks optimal whisper settings
- Multi-language support with configurable transcription parameters

**Terminal**
- Full Termux terminal (bash, apt, Node.js, Python, Git, SSH...)
- Bluetooth keyboard support
- Configurable fonts (Fira Code, Hack, Inconsolata, Iosevka, JetBrains Mono)
- Color scheme customization
- 12 user-configurable macros with `{tag}` syntax for key combos

**Touch UI (VR-friendly)**
- GameBoy-style touch control panel with D-pad, modifiers, and macros
- Compact toolbar mode (48dp bar with swipe macro pages)
- Fullscreen mode for maximum terminal space
- Large touch targets — comfortable on a phone, designed for VR raycast input
- Haptic feedback on button presses

## Quick Start

1. **Download** the latest APK from [Releases](https://github.com/BaLaurent/voidTerm/releases)

2. **Enable USB debugging** — Developer Options on a phone/tablet, or [Developer Mode](https://developer.oculus.com/documentation/native/android/mobile-device-setup/) on a Quest

3. **Install via ADB** (or just open the APK on-device to sideload):
   ```bash
   adb install voidterm.apk
   ```

4. **Disable phantom process killing** (recommended on Android 12+ for long-running processes like Claude Code):
   ```bash
   adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent"
   adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"
   adb shell settings put global settings_enable_monitor_phantom_procs false
   ```

5. **Launch** VoidTerm from your app library (on Quest: under "Unknown Sources")

See [Quest Setup Guide](docs/QUEST_SETUP.md) for headset-specific configuration and [Claude Code Setup](docs/CLAUDE_CODE_SETUP.md) to get Claude running.

## How It Works

```
┌─────────────────────────────────────────────────────────────┐
│                      Android Device                         │
│                                                             │
│  Mic button / Controller (A/X) ──► input handler            │
│                              │                              │
│                              ▼                              │
│  Microphone ──► AudioCapture ──► VoiceInputManager          │
│                 (16kHz PCM)       (state machine)           │
│                                       │                     │
│                                       ▼                     │
│                    TranscriptionEngine                      │
│            whisper.cpp (JNI)  │  Parakeet (ONNX)            │
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

### STT Engines

VoidTerm abstracts speech-to-text behind a `TranscriptionEngine` interface with two implementations:

- **whisper.cpp** — compiled via JNI into two ARM64 variants: `whisper_jni` (baseline ARM NEON, all arm64 devices) and `whisper_jni_v8fp16` (ARMv8.2-A half-precision, ~15-30% faster on Quest XR2). The optimal variant is selected at runtime.
- **Parakeet TDT v3** — NVIDIA's Token-and-Duration Transducer running through ONNX Runtime (preprocessor + Conformer encoder + transducer decoder). Models are downloaded on demand. Often faster and more accurate on shorter utterances.

The active engine is chosen in Settings; both run fully on-device.

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
- **Speech-to-Text:** [whisper.cpp](https://github.com/ggerganov/whisper.cpp) v1.7.3 (MIT) — ARM64 NEON optimized — and [Parakeet TDT v3](https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx) via [ONNX Runtime](https://onnxruntime.ai/)
- **Build:** Android NDK r25+, CMake 3.22.1+, Gradle 8.2
- **Target:** Any Android 9+ (API 28) device, arm64-v8a — phones, tablets, and Meta Quest 2 / 3 / Pro

## License

[GPL-3.0](LICENSE)

VoidTerm is a fork of [Termux](https://termux.dev) (GPL-3.0). whisper.cpp is used under the [MIT license](https://github.com/ggerganov/whisper.cpp/blob/master/LICENSE).
