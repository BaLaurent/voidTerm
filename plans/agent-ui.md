# Agent: agent-ui

**Role:** All UI components — transcription overlay, Quest display adaptations, controller input, onboarding
**Phases active:** 2, 3, 5

---

## File Ownership

| Permission | Path |
|---|---|
| WRITE | `app/src/main/java/com/voidterm/voice/TranscriptionOverlay.java` |
| WRITE | `app/src/main/java/com/voidterm/input/QuestInputHandler.java` |
| WRITE | `app/src/main/java/com/voidterm/onboarding/OnboardingFlow.java` |
| WRITE | `app/src/main/res/layout/transcription_overlay.xml` |
| WRITE | `app/src/main/res/layout/onboarding_dialog.xml` |
| WRITE | `app/src/main/res/values/quest_defaults.xml` |
| WRITE | `app/src/main/res/values/strings.xml` (new string resources only) |
| READ | `app/src/main/java/com/voidterm/contracts/*.java` |
| READ | All |

---

## Phase 2 — Independent Components

### Phase Gate (entry)
- T1.2 complete (contracts — VoiceState, TranscriptionListener)
- T1.1 complete (project structure exists)

### Task T2.3 — TranscriptionOverlay

- [ ] **T2.3**

**Description:** The visual overlay displayed above the terminal during voice input. A `FrameLayout` with semi-transparent background that shows different content depending on the current `VoiceState`.

**Visual states (from specs 4.6):**

| VoiceState | Content |
|---|---|
| RECORDING | Mic icon + volume bar (animated) + "Recording..." label |
| TRANSCRIBING | Spinner/hourglass icon + "Transcribing..." label |
| SHOWING_RESULT | Read-only text area with transcription + Send button + Cancel button |
| EDITING | Editable text area (cursor active, BT keyboard input) + Send button + Cancel button |
| IDLE / ERROR | Hidden (GONE visibility) or error message |

**Key behaviors:**
- `setState(VoiceState state)` — switches visible content, hides/shows overlay
- `showTranscription(String text)` — populates text area (for SHOWING_RESULT state)
- `setTranscriptionListener(TranscriptionListener listener)` — wires Send/Cancel/Edit callbacks
- `setVolumeLevel(float level)` — updates volume bar width during recording (0.0-1.0)
- Send button → calls `listener.onSendRequested(currentText)`
- Cancel button → calls `listener.onCancelRequested()`
- Clicking text area in SHOWING_RESULT → transitions to EDITING via `listener.onEditStarted()`
- Enter key in EDITING → calls `listener.onSendRequested(editedText)`
- Escape key in EDITING/SHOWING_RESULT → calls `listener.onCancelRequested()`

**Layout XML intent (transcription_overlay.xml):**
- Root: `FrameLayout`, match_parent, semi-transparent dark background (`#CC000000`)
- Child container: centered, padded, rounded corners
- Volume bar: thin horizontal bar, width animated by volume level
- Text area: monospace font, 18sp, white text, scrollable
- Buttons: large touch targets (min 48dp height, ideally 56dp for Quest raycast)
- Send button: green accent, "Send" label
- Cancel button: red accent, "Cancel" label

**Input dependencies:** T1.2 (VoiceState enum, TranscriptionListener interface)
**Output artifacts:**
- `app/src/main/java/com/voidterm/voice/TranscriptionOverlay.java`
- `app/src/main/res/layout/transcription_overlay.xml`

**Acceptance criteria:**
- Extends `FrameLayout`
- 4 distinct visual states matching specs table
- Overlay is GONE when IDLE
- Send/Cancel buttons fire TranscriptionListener callbacks
- Text is editable in EDITING state, read-only in SHOWING_RESULT
- Enter key sends, Escape key cancels
- Volume bar updates smoothly during RECORDING
- Touch targets >= 48dp (Quest raycast friendly)
- Monospace font in text area

---

### Task T2.4 — Quest Default Values

- [ ] **T2.4**

**Description:** Create the Android resource XML file with Quest-optimized default values.

**Values (from specs 5.1):**

| Resource Name | Value | Description |
|---|---|---|
| `quest_default_font_size` | `20sp` | Terminal font size |
| `quest_extra_key_height` | (150% of Termux default) | Extra keys button height |
| `quest_ptt_key_default` | keycode for Quest A button | Default PTT key |
| `quest_min_touch_target` | `48dp` | Minimum touch target for raycast |

**Input dependencies:** T1.1 (project structure)
**Output artifacts:**
- `app/src/main/res/values/quest_defaults.xml`

**Acceptance criteria:**
- Valid Android resource XML
- Font size is 20sp
- Extra key sizing defined
- All values have descriptive names prefixed with `quest_`

---

## Phase 3 — Core Systems

### Phase Gate (entry)
- T1.2 complete (contracts)
- T2.4 complete (quest defaults, for OnboardingFlow references)

### Task T3.3 — QuestInputHandler

- [ ] **T3.3**

**Description:** Maps Meta Quest controller buttons to VoidTerm actions. Intercepts Android `KeyEvent` and routes them appropriately.

**Button mappings (from specs 5.2):**

| Action | Input | Implementation |
|---|---|---|
| Pointer / click | Trigger (index) | Native Android — no code needed |
| Push-to-Talk | Button A or X (configurable) | `onKeyDown` → `voiceManager.onPushToTalkPressed()`, `onKeyUp` → `voiceManager.onPushToTalkReleased()` |
| Scroll terminal | Thumbstick up/down | Native Android — no code needed |
| Back | Button B or Y | Native Android — no code needed |

**Key behaviors:**
- Constructor takes `VoiceInputManager` reference
- `onKeyDown(int keyCode, KeyEvent event)` — if keyCode matches PTT key, call `voiceManager.onPushToTalkPressed()`, return true (consumed)
- `onKeyUp(int keyCode, KeyEvent event)` — if keyCode matches PTT key, call `voiceManager.onPushToTalkReleased()`, return true
- `setPttKeyCode(int keyCode)` — allows runtime configuration of PTT button
- Double-tap detection: if PTT pressed twice within 300ms, call `voiceManager.onDoubleTap()` (cancel recording)
- Returns false for unhandled keys (let Android handle normally)

**Input dependencies:** T1.2 (contracts — VoiceState)
**Output artifacts:**
- `app/src/main/java/com/voidterm/input/QuestInputHandler.java`

**Acceptance criteria:**
- PTT press/release correctly routed to VoiceInputManager
- Double-tap detected within 300ms window
- Unhandled keys return false (no interference with normal input)
- PTT keycode configurable at runtime
- Does not consume keys when VoiceInputManager is null (graceful degradation)

---

### Task T3.4 — OnboardingFlow

- [ ] **T3.4**

**Description:** First-launch assistant that guides users through setting up Claude Code on the Quest.

**Flow (from specs US-04):**
1. Welcome dialog with VoidTerm branding
2. Step: install packages — displays `pkg update && pkg install nodejs git`, with "Run" button that executes in terminal
3. Step: install Claude Code — displays `npm i -g @anthropic-ai/claude-code`, copiable
4. Step: test microphone — record 3s, play back, confirm working
5. Completion — dismiss, mark as completed in SharedPreferences
6. Every step is skippable (Skip button always visible)

**Key behaviors:**
- `showIfFirstLaunch()` — checks `SharedPreferences` for `onboarding_completed` flag
- Uses `AlertDialog` or custom dialog with step-by-step navigation
- "Run" button for package install executes command in the active terminal session
- "Copy" button for Claude Code install copies to clipboard
- Mic test: records 3 seconds, plays back via `AudioTrack`
- `setOnCompleteListener(Runnable)` — called when user finishes or skips all steps
- After completion (or full skip), sets `onboarding_completed = true`

**Layout XML intent (onboarding_dialog.xml):**
- Stepper-style layout (step indicators at top)
- Large readable text (18sp+)
- Code blocks in monospace with copy buttons
- Large skip/next/run buttons (Quest raycast)

**Input dependencies:**
- T1.2 (contracts)
- T2.4 (quest_defaults — for consistent styling)

**Output artifacts:**
- `app/src/main/java/com/voidterm/onboarding/OnboardingFlow.java`
- `app/src/main/res/layout/onboarding_dialog.xml`

**Acceptance criteria:**
- Shows only on first launch (SharedPreferences check)
- All 4 steps present: welcome, pkg install, Claude Code, mic test
- Every step is skippable
- "Run" button triggers terminal command execution (via callback, not direct PTY access)
- Mic test records and plays back
- Completion flag persisted
- Dialog dismissible at any point

---

## Phase 5 — Documentation & Polish

### Phase Gate (entry)
- T4.1 complete (full integration done)

### Task T5.5 — UI Polish

- [ ] **T5.5**

**Description:** Review and refine all UI components for Quest readability and usability.

**Polish items:**
- Verify TranscriptionOverlay contrast on Quest display (semi-transparent background readable over terminal)
- Verify button touch targets work with Quest raycast (controller pointing)
- Verify volume bar animation smoothness
- Verify onboarding dialog readability at Quest viewing distance
- Ensure overlay doesn't obscure critical terminal output (position at top or bottom, not center)
- Verify text editing in overlay works with BT keyboard (cursor, selection, delete)

**Input dependencies:** T4.1 (all components wired and testable)
**Output artifacts:**
- Updated UI component files (any of the owned files may be modified)

**Acceptance criteria:**
- All UI elements readable at Quest arm's-length distance
- Touch targets >= 48dp
- Overlay position doesn't cover terminal prompt line
- BT keyboard editing works in overlay text area
- No visual glitches in state transitions
