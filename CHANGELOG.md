# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
