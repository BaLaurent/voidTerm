# Agent: agent-integration

**Role:** TermuxActivity modifications, terminal-view changes, extra keys config, documentation, final wiring
**Phases active:** 4, 5, 6

---

## File Ownership

| Permission | Path |
|---|---|
| WRITE | `app/src/main/java/com/voidterm/app/TermuxActivity.java` |
| WRITE | `app/src/main/java/com/voidterm/app/TermuxTerminalSessionClient.java` |
| WRITE | `terminal-view/` (font default modifications) |
| WRITE | Extra keys configuration files |
| WRITE | `docs/BUILDING.md` |
| WRITE | `docs/QUEST_SETUP.md` |
| WRITE | `docs/CLAUDE_CODE_SETUP.md` |
| WRITE | `README.md` |
| WRITE | `CHANGELOG.md` |
| WRITE | `CONTRIBUTING.md` |
| WRITE | `LICENSE` |
| WRITE | `.github/ISSUE_TEMPLATE/bug_report.md` |
| WRITE | `.github/ISSUE_TEMPLATE/feature_request.md` |
| READ | All (especially all Phase 2-3 outputs) |

---

## Phase 4 — Activity Integration

### Phase Gate (entry)
- T3.1 complete (VoiceInputManager — full state machine ready)
- T3.2 complete (WhisperBridge — model loading and transcription ready)
- T3.3 complete (QuestInputHandler — controller mapping ready)
- T3.4 complete (OnboardingFlow — first-launch wizard ready)
- T2.4 complete (quest_defaults.xml — display values ready)

### Task T4.1 — TermuxActivity Integration

- [ ] **T4.1**

**Description:** This is the central wiring task. Modify the existing Termux `TermuxActivity.java` to instantiate and connect all VoidTerm components.

**Modifications to TermuxActivity:**

1. **onCreate additions:**
   - Load whisper model: `WhisperBridge whisper = new WhisperBridge(); whisper.loadModel(this, "ggml-base.bin", callback)`
   - Create TranscriptionOverlay and add to layout hierarchy (above terminal, below extra keys)
   - Create VoiceInputManager with overlay + VoiceInputCallback
   - Create QuestInputHandler with VoiceInputManager
   - Trigger OnboardingFlow: `new OnboardingFlow(this).showIfFirstLaunch()`

2. **onKeyDown / onKeyUp override:**
   - Delegate to `QuestInputHandler.onKeyDown()` / `onKeyUp()` first
   - If consumed (returns true), don't pass to Termux
   - If not consumed, pass to original Termux handler

3. **Layout modifications:**
   - Insert TranscriptionOverlay `FrameLayout` into the existing Termux layout
   - Position: above terminal view, overlapping (FrameLayout stacking)

4. **VoiceInputCallback implementation:**
   - `onVoiceTextReady(text)` → delegate to TermuxTerminalSessionClient for PTY injection
   - `onVoiceStateChanged(state)` → log state transitions (for debugging)

5. **onDestroy additions:**
   - Call `voiceInputManager.destroy()`
   - Call `whisperBridge.release()`

6. **Extra keys mic button:**
   - Add 🎤 button to extra keys row configuration
   - Wire mic button tap to `voiceInputManager.onPushToTalkPressed()` / `onPushToTalkReleased()` (toggle mode for touch: first tap = start, second tap = stop)

**Input dependencies:**
- T3.1 (`VoiceInputManager` — public API)
- T3.2 (`WhisperBridge` — public API)
- T3.3 (`QuestInputHandler` — public API)
- T3.4 (`OnboardingFlow` — public API)
- T2.3 (`TranscriptionOverlay` — public API)
- T2.4 (`quest_defaults.xml` — values)
- T1.2 (contracts — VoiceInputCallback, VoiceState)

**Output artifacts:**
- `app/src/main/java/com/voidterm/app/TermuxActivity.java` (modified)

**Acceptance criteria:**
- All 5 components instantiated in onCreate
- Key events delegated to QuestInputHandler before Termux
- TranscriptionOverlay visible in layout
- Voice text injection reaches terminal PTY
- Onboarding shows on first launch
- Mic button in extra keys triggers PTT
- Resources cleaned up in onDestroy
- No regressions to existing Termux terminal functionality

---

### Task T4.2 — TermuxTerminalSessionClient Voice Hook

- [ ] **T4.2**

**Description:** Add the voice text injection capability to `TermuxTerminalSessionClient`. This is how transcribed text reaches the terminal PTY.

**Modification:**
- Add method `injectVoiceText(String text)` that writes text bytes to the active terminal session's PTY
- Uses existing Termux `TerminalSession.write()` method
- Does NOT append a newline — the user must press Enter to execute (specs: "text is never auto-executed")
- Handles the case where no terminal session is active (no-op)

**Input dependencies:** T3.1 (VoiceInputManager calls VoiceInputCallback, which routes here)
**Output artifacts:**
- `app/src/main/java/com/voidterm/app/TermuxTerminalSessionClient.java` (modified)

**Acceptance criteria:**
- `injectVoiceText(text)` writes to active PTY
- No newline appended (user must press Enter)
- No-op if no active session
- Does not interfere with existing terminal write operations
- Text appears at cursor position in terminal

---

### Task T4.3 — Terminal View Font Default

- [ ] **T4.3**

**Description:** Modify `terminal-view` module to use the Quest default font size (20sp) instead of Termux's smaller default.

**Modification:**
- Find the default font size constant in terminal-view source
- Change it to read from `quest_defaults.xml` resource (`quest_default_font_size`, 20sp)
- If resource reading is complex, simply change the hardcoded default to 20

**Input dependencies:** T2.4 (quest_defaults.xml exists with font size value)
**Output artifacts:**
- Modified file(s) in `terminal-view/`

**Acceptance criteria:**
- Default terminal font size is 20sp on fresh install
- Font size change doesn't break existing font scaling logic
- Existing configurable font size (if any) still works

---

### Task T4.4 — Extra Keys Configuration

- [ ] **T4.4**

**Description:** Modify Termux extra keys row to include a microphone button and increase button sizes for Quest raycast compatibility.

**Modifications:**
- Add 🎤 (mic) button to the extra keys row
- Increase default button height to 150% of Termux standard (from quest_defaults.xml)
- Mic button behavior: toggle Push-to-Talk
  - First tap while IDLE → start recording (`onPushToTalkPressed`)
  - Second tap while RECORDING → stop recording (`onPushToTalkReleased`)
  - Tap while SHOWING_RESULT/EDITING → no-op (overlay handles interaction)
- Visual feedback: mic button changes appearance when recording (red tint or filled icon)

**Input dependencies:**
- T3.1 (VoiceInputManager — for PTT control)
- T2.4 (quest_defaults.xml — button sizing)

**Output artifacts:**
- Modified extra keys configuration file(s)

**Acceptance criteria:**
- 🎤 button visible in extra keys row
- Button toggles PTT state correctly
- Button sizes increased for Quest
- Visual recording indicator on mic button
- Other extra keys still function normally

---

## Phase 5 — Documentation & Polish

### Phase Gate (entry)
- T4.1, T4.2, T4.3, T4.4 complete (all integration done)

### Task T5.1 — BUILDING.md

- [ ] **T5.1**

**Description:** Write build documentation covering how to compile VoidTerm from source.

**Content outline:**
- Prerequisites: JDK 17, Android SDK, NDK r25+, CMake 3.10+
- Clone with submodules: `git clone --recursive`
- whisper.cpp submodule setup
- Gradle build: `./gradlew assembleRelease`
- APK location: `app/build/outputs/apk/release/`
- APK signing for sideloading
- Troubleshooting common build issues

**Input dependencies:** T4.1 (build must be complete to document accurately)
**Output artifacts:** `docs/BUILDING.md`

**Acceptance criteria:**
- A developer can follow the doc to build the APK from scratch
- All prerequisites listed
- Build commands are copy-pasteable

---

### Task T5.2 — QUEST_SETUP.md

- [ ] **T5.2**

**Description:** Write Quest setup documentation for end users.

**Content outline:**
- Enable developer mode on Quest
- Install via `adb install voidterm.apk`
- Phantom process killing fix (ADB commands from specs 5.3)
- Bluetooth keyboard pairing
- Controller button mapping
- Recommended Quest settings (hand tracking off during terminal use, etc.)

**Input dependencies:** T4.1
**Output artifacts:** `docs/QUEST_SETUP.md`

**Acceptance criteria:**
- Phantom process killing commands are exact copy from specs
- Step-by-step sideloading instructions
- BT keyboard pairing covered

---

### Task T5.3 — CLAUDE_CODE_SETUP.md

- [ ] **T5.3**

**Description:** Write Claude Code installation guide specific to VoidTerm.

**Content outline:**
- `pkg update && pkg install nodejs git`
- `npm i -g @anthropic-ai/claude-code`
- API key configuration: `export ANTHROPIC_API_KEY=...` in `.bashrc`
- First run: `claude`
- Known quirks on ARM64/Termux
- Troubleshooting (npm permission issues, network errors)

**Input dependencies:** T4.1
**Output artifacts:** `docs/CLAUDE_CODE_SETUP.md`

**Acceptance criteria:**
- Commands are tested on Termux ARM64
- API key storage documented with security note
- Common failure modes addressed

---

### Task T5.4 — Repository Governance Files

- [ ] **T5.4**

**Description:** Create standard open-source repository files.

**Files:**
- `README.md` — project overview, screenshot placeholder, quick start, build link, license
- `CHANGELOG.md` — v1.0.0 entry with all MUST HAVE features
- `CONTRIBUTING.md` — branch strategy (main/develop/feature/*), PR process, code style
- `LICENSE` — GPL-3.0 full text
- `.github/ISSUE_TEMPLATE/bug_report.md` — structured bug report (Quest model, firmware, steps to reproduce)
- `.github/ISSUE_TEMPLATE/feature_request.md` — structured feature request

**Input dependencies:** T4.1 (project must be feature-complete to document accurately)
**Output artifacts:**
- `README.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, `LICENSE`
- `.github/ISSUE_TEMPLATE/bug_report.md`
- `.github/ISSUE_TEMPLATE/feature_request.md`

**Acceptance criteria:**
- README has project description, features list, install/build links
- CHANGELOG follows Keep a Changelog format
- CONTRIBUTING describes branch strategy from specs 9.3
- LICENSE is GPL-3.0
- Issue templates have required fields

---

## Phase 6 — Validation & Release

### Phase Gate (entry)
- All Phase 5 tasks complete

### Task T6.1 — End-to-End Validation

- [ ] **T6.1**

**Description:** Verify all user stories (US-01 through US-04) acceptance criteria against the implemented code. This is a review task, not implementation.

**Checklist:**

**US-01 — Claude Code on Quest:**
- [ ] App launches as 2D panel
- [ ] Bash shell available
- [ ] `pkg install nodejs` works (conceptual — Termux packages)
- [ ] `npm i -g @anthropic-ai/claude-code` works
- [ ] `claude` launches with ANSI colors
- [ ] Streaming responses display correctly
- [ ] Scroll works

**US-02 — Voice dictation:**
- [ ] PTT shows recording indicator
- [ ] Release shows "Transcribing..."
- [ ] Transcribed text in overlay
- [ ] User can review, edit, send, cancel
- [ ] Text never auto-executed
- [ ] Latency target: ≤5s for 10s audio (Quest 3)

**US-03 — Bluetooth keyboard:**
- [ ] All keys functional
- [ ] Ctrl+C, Ctrl+D, Ctrl+Z work
- [ ] Voice and keyboard coexist

**US-04 — First launch:**
- [ ] Welcome dialog shown
- [ ] Package install offered
- [ ] Claude Code command displayed
- [ ] Mic test available
- [ ] All steps skippable

**Input dependencies:** T5.1, T5.2, T5.3, T5.4, T5.5 (all docs and polish done)
**Output artifacts:** Validation report (document gaps if any)

**Acceptance criteria:**
- All US acceptance criteria verified in code
- Any gaps documented with severity and workaround
- Go/no-go recommendation for v1.0 release
