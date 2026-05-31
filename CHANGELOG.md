# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2026-05-31

### Added
- NVIDIA Parakeet TDT v3 as a second on-device STT engine, selectable in Settings — runs via ONNX Runtime with on-demand model download
- `TranscriptionEngine` abstraction with WhisperEngine and ParakeetEngine implementations
- Foreground `TerminalService` so terminal sessions survive Activity destruction
- Multi-session support with a left navigation drawer
- Streaming transcription mode (text flows to the overlay in real-time)
- Device auto-tuning (DeviceProfiler) that benchmarks and picks optimal whisper settings
- Configurable transcription settings (language, translate, beam search, temperature, threads, suppress non-speech)
- Configurable audio preprocessing (gain, pre-emphasis, high-pass filter, normalization) with an A/B debug dialog
- Expanded from 4 to 12 paginated macros with `{tag}` key-combination syntax
- CompactPanel and CompactToolbar panel modes, plus a fullscreen (toolbar-only) mode
- Interface theming (4 themes) across control panels, session drawer, and settings
- Full-screen SettingsActivity replacing the old dialog
- Configurable back-key behavior and independent Volume Up/Down actions (escape, toggle keyboard, macro, voice)
- Release APK signing configuration (credentials read from a gitignored keystore.properties)

### Changed
- Broadened supported platform: runs on any Android 9+ (arm64) device — phones, tablets, and Meta Quest — not Quest only
- Speech-to-text now sits behind a pluggable engine abstraction

### Fixed
- Parakeet TDT decoding now uses the duration head and only advances decoder state on non-blank emission — eliminates spurious "..." in silent regions and occasional empty output, and speeds up decoding
- Numerous code-review and audit findings across the voice pipeline, JNI, UI, lifecycle, and security layers
- `whisper_full()` hanging indefinitely on silence or unsupported audio
- Path remapping for apt-installed binaries (e.g. SSH) via dpkg post-invoke hook

## [1.0.0] - 2026-02-21

### Added
- Fork of Termux with VoidTerm branding
- whisper.cpp integration via JNI for local speech-to-text (ARM64 NEON)
- Push-to-Talk voice input with controller A/X button or mic extra key
- TranscriptionOverlay with recording, transcribing, result, and editing states
- VoiceInputManager state machine orchestrating audio capture and transcription
- Quest controller input handler with configurable PTT button
- Extra keys row with mic button and 150% sizing for Quest raycast
- Terminal font size default changed to 20sp for Quest readability
- First-launch onboarding wizard (package install, Claude Code setup, mic test)
- Voice text injection into terminal PTY (no auto-execution)
- GitHub Actions CI for ARM64 APK builds
- Documentation: BUILDING.md, QUEST_SETUP.md, CLAUDE_CODE_SETUP.md
