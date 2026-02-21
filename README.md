# VoidTerm

A Termux fork with local voice input for Meta Quest. Speak your prompts, code in VR.

## What is VoidTerm?

VoidTerm combines [Termux](https://termux.dev) with [whisper.cpp](https://github.com/ggerganov/whisper.cpp) to provide a full Linux terminal on Meta Quest with local speech-to-text. It is designed for running [Claude Code](https://docs.anthropic.com/en/docs/claude-code) hands-free in VR.

**Key features:**

- Full Termux terminal (bash, apt, Node.js, Git, SSH)
- Local voice-to-text via whisper.cpp (no cloud, no latency, no data sent)
- Push-to-Talk with Quest controllers (A/X button)
- Transcription overlay with edit-before-send
- Quest-optimized UI (20sp font, 150% extra keys)
- Bluetooth keyboard support
- Claude Code ready out of the box
- First-launch onboarding wizard

## Quick Start

1. **Download** the latest APK from [Releases](https://github.com/user/voidterm/releases)
2. **Enable Developer Mode** on your Quest
3. **Install:** `adb install voidterm.apk`
4. **Disable phantom process killing** (required for Claude Code):
   ```bash
   adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent"
   adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"
   adb shell settings put global settings_enable_monitor_phantom_procs false
   ```
5. **Launch** VoidTerm from Unknown Sources in your Quest app library
6. **Follow** the onboarding wizard to install Claude Code

## Documentation

- [Building from Source](docs/BUILDING.md) -- Compile VoidTerm yourself
- [Quest Setup](docs/QUEST_SETUP.md) -- Install and configure on Meta Quest
- [Claude Code Setup](docs/CLAUDE_CODE_SETUP.md) -- Get Claude Code running in VoidTerm

## Architecture

VoidTerm is a native Android app (Termux fork) targeting ARM64 exclusively.

```
Quest Controller ──Push-to-Talk──> VoiceInputManager
Quest Microphone ──Audio PCM────> whisper.cpp (JNI, ARM64 NEON)
whisper.cpp ──────Transcribed───> TranscriptionOverlay
User confirms ───────Enter──────> Terminal PTY
Terminal PTY ────────────────────> bash > Claude Code > Anthropic API
BT Keyboard ────────────────────> Terminal UI (standard input)
```

| Component | Source |
|-----------|--------|
| Terminal | Termux (terminal-emulator, terminal-view) |
| Voice STT | whisper.cpp via JNI (base model, 142 MB) |
| Audio capture | Android AudioRecord (16kHz mono float32) |
| UI overlay | TranscriptionOverlay (Android FrameLayout) |
| Controller input | QuestInputHandler (A/X = PTT, B/Y = Back) |

## Tech Stack

- **Base:** Termux (GPL-3.0)
- **STT:** whisper.cpp (MIT) -- ARM64 NEON optimized
- **Build:** Android NDK r25+, CMake 3.22.1+, Gradle 8.2
- **Target:** Meta Quest 2 / 3 / Pro (arm64-v8a only)
- **Runtime:** Node.js (for Claude Code), bash

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on branching, pull requests, and code style.

## License

[GPL-3.0](LICENSE)

VoidTerm is a fork of [Termux](https://termux.dev) (GPL-3.0). whisper.cpp is used under the MIT license.
