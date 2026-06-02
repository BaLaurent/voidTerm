# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.0] - 2026-06-02

### Added
- Advanced physical-key gestures: double-tap, triple-tap, long-press, and a Vol+ & Vol− combo layered on top of the single-tap actions for Volume Up, Volume Down, and Back (15 configurable slots). Per-key "Advanced" expanders, a "Combo" subsection, and a global sensitivity preset (Reactive / Normal / Tolerant). Backed by a deterministic, unit-tested `KeyGestureDetector` with a safety-critical Back-key consumption contract
- In-app whisper.cpp model download: a built-in catalog of 31 ggml variants (tiny → large, `.en` and quantized) downloadable directly from Settings — no more manual file picker required
- Parakeet int8/fp32 quantization selector: choose between int8 (~670 MB) and fp32 (~2.55 GB) models, with per-quantization download / activate / delete, auto-activation on download, and an adaptive memory note that reads the device's real RAM and recommends per tier
- Long-audio Parakeet chunking: long recordings are split at natural silences (avoids cutting sentences mid-stream and prevents encoder OOM); the audio-capture 30 s cap is lifted in favor of a 2-minute safety ceiling
- Configurable Parakeet advanced settings (chunk window, overlap, silence threshold, thread count) and ONNX session tuning (optimization level + intra-op threads)

### Changed
- Parakeet TDT v3 is now the default transcription engine (better accuracy than whisper.cpp on Quest)
- The download stack is now generic: a single `ModelDownloadService` foreground service drives both Whisper and Parakeet downloads via a `DownloadJob` policy interface, sharing the extracted `HttpModelDownloader` transfer
- Importing a custom whisper model via the file picker now also switches the active engine to whisper

## [1.1.1] - 2026-06-01

### Added
- Direct send for both engines: Whisper *and* Parakeet can inject transcribed text straight into the terminal, skipping the review/validation overlay (previously Whisper-only)
- Auto-submit: optional sub-toggle that presses Enter after the final text, for fully hands-free use in VR
- Background Parakeet model download in a dedicated foreground service, surviving Settings navigation and app backgrounding, with progress notification, Cancel action, and a wake lock
- "Delete Models" button (with confirmation) to force a clean re-download

### Changed
- Direct-send is now an engine capability (`TranscriptionEngine.isDirectToTerminal()`) instead of a whisper-only flag
- Renamed preference `whisper_streaming` → `voice_direct_send` (migrated transparently on first read)

### Fixed
- Auto-submit produced a newline ("Shift+Enter") instead of submitting the command with Parakeet; the `\r` is now deferred (50 ms) so the TUI reads it as a distinct Enter keystroke rather than a coalesced paste newline

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
