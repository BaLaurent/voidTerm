# VoidTerm — Implementation Plan

**Version:** 1.0
**Source:** specs.md v1.0
**Agents:** 5
**Phases:** 6

---

## 1. Project Overview

### Goals
Fork Termux into VoidTerm with integrated local voice input (whisper.cpp via JNI) for Meta Quest. Users run Claude Code in a terminal, dictating prompts by voice.

### Constraints
- Single APK, no external dependencies beyond Termux ecosystem
- ARM64 only (Meta Quest 2/3/Pro)
- GPL-3.0 license (compatible with all deps)
- 2D flat panel, no spatial VR
- Voice processing 100% local (whisper.cpp)
- Solo developer, 11-week horizon

### Tech Stack
| Layer | Technology |
|---|---|
| Base | Termux (GPL-3.0) — Android Java |
| Terminal | terminal-emulator (Apache-2.0), terminal-view (Apache-2.0) |
| Voice STT | whisper.cpp (MIT) via JNI, ARM64 NEON |
| Audio | Android AudioRecord API (PCM float32, 16kHz mono) |
| Build | Gradle + Android NDK (CMake) |
| CI | GitHub Actions |
| Target | Android API 29+, ARM64 |

---

## 2. Agent Roster & File Ownership

### agent-build
**Role:** Project scaffolding, Gradle configuration, shared contracts, CI

| Permission | Paths |
|---|---|
| WRITE | `build.gradle`, `settings.gradle`, `gradle.properties` |
| WRITE | `app/build.gradle` |
| WRITE | `app/src/main/java/com/voidterm/contracts/*.java` |
| WRITE | `app/src/main/AndroidManifest.xml` (voice/audio permissions) |
| WRITE | `.github/workflows/build-arm64.yml` |
| WRITE | `.gitmodules` |
| READ | All |

### agent-whisper
**Role:** whisper.cpp native integration (C++ JNI layer, CMake build)

| Permission | Paths |
|---|---|
| WRITE | `app/src/main/jni/whisper_jni.cpp` |
| WRITE | `app/src/main/jni/CMakeLists.txt` |
| WRITE | `app/src/main/jni/whisper.cpp/` (submodule reference) |
| READ | `app/src/main/java/com/voidterm/contracts/*.java` |
| READ | All |

### agent-voice
**Role:** Java voice input system (audio capture, state machine, Whisper wrapper)

| Permission | Paths |
|---|---|
| WRITE | `app/src/main/java/com/voidterm/voice/VoiceInputManager.java` |
| WRITE | `app/src/main/java/com/voidterm/voice/AudioCapture.java` |
| WRITE | `app/src/main/java/com/voidterm/voice/WhisperBridge.java` |
| READ | `app/src/main/java/com/voidterm/contracts/*.java` |
| READ | `app/src/main/jni/whisper_jni.cpp` (JNI function signatures) |
| READ | All |

### agent-ui
**Role:** All UI components — overlay, Quest adaptations, controller input, onboarding

| Permission | Paths |
|---|---|
| WRITE | `app/src/main/java/com/voidterm/voice/TranscriptionOverlay.java` |
| WRITE | `app/src/main/java/com/voidterm/input/QuestInputHandler.java` |
| WRITE | `app/src/main/java/com/voidterm/onboarding/OnboardingFlow.java` |
| WRITE | `app/src/main/res/layout/transcription_overlay.xml` |
| WRITE | `app/src/main/res/layout/onboarding_dialog.xml` |
| WRITE | `app/src/main/res/values/quest_defaults.xml` |
| WRITE | `app/src/main/res/values/strings.xml` (new strings only) |
| READ | `app/src/main/java/com/voidterm/contracts/*.java` |
| READ | All |

### agent-integration
**Role:** TermuxActivity modifications, terminal-view changes, extra keys, docs, wiring

| Permission | Paths |
|---|---|
| WRITE | `app/src/main/java/com/voidterm/app/TermuxActivity.java` |
| WRITE | `app/src/main/java/com/voidterm/app/TermuxTerminalSessionClient.java` |
| WRITE | `terminal-view/` (font size default changes) |
| WRITE | Extra keys configuration files |
| WRITE | `docs/BUILDING.md`, `docs/QUEST_SETUP.md`, `docs/CLAUDE_CODE_SETUP.md` |
| WRITE | `README.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, `LICENSE` |
| WRITE | `.github/ISSUE_TEMPLATE/*.md` |
| READ | All (especially all agent outputs from prior phases) |

---

## 3. Dependency Graph (DAG)

```
T1.1 ─────────────────────────────────────────────┐
T1.2 ──┬──────────────────────────────────────────┐│
       │                                          ││
       ├──→ T2.1 (whisper JNI) ──→ T3.2 ─────────┤│
       │                                          ││
       ├──→ T2.2 (AudioCapture) ──→ T3.1 ────────┤│
       │                                          ││
       ├──→ T2.3 (TranscriptionOverlay) ──→ T3.1 ─┤│
       │                                          ││
       ├──→ T2.4 (quest_defaults.xml) ────────────┤│
       │                                          ││
       └──→ T2.5 (CI workflow) ───────────────────┤│
                                                   ││
T3.1 (VoiceInputManager) ─────────────────────────┤│
T3.2 (WhisperBridge) ─────────────────────────────┤│
T3.3 (QuestInputHandler) ─────────────────────────┤│
T3.4 (OnboardingFlow) ────────────────────────────┤│
                                                   ││
T4.1 (TermuxActivity integration) ←────────────────┘│
T4.2 (TerminalSessionClient hook) ←────────────────┘│
T4.3 (terminal-view font) ←──────────────────────────┘
T4.4 (Extra keys + PTT button)

T5.1 (BUILDING.md) ──────────────────────────────┐
T5.2 (QUEST_SETUP.md) ───────────────────────────┤
T5.3 (CLAUDE_CODE_SETUP.md) ─────────────────────┤
T5.4 (README + repo governance) ─────────────────┤
T5.5 (UI polish) ────────────────────────────────┤
                                                   │
T6.1 (E2E validation) ←───────────────────────────┘
T6.2 (Release prep) ←─────────────────────────────┘
```

---

## 4. Execution Phases

### Phase 1 — Scaffolding & Contracts
**Barrier:** All shared interfaces and project structure must exist before Phase 2.

| Task ID | Agent | Description | Depends On | Output Artifacts |
|---|---|---|---|---|
| T1.1 | agent-build | Create project directory structure, root Gradle files, app/build.gradle with NDK + JNI config, AndroidManifest.xml with permissions | — | `build.gradle`, `settings.gradle`, `app/build.gradle`, `AndroidManifest.xml` |
| T1.2 | agent-build | Define shared contracts: `VoiceState` enum, `VoiceInputCallback` interface, `TranscriptionListener` interface | — | `contracts/VoiceState.java`, `contracts/VoiceInputCallback.java`, `contracts/TranscriptionListener.java` |

### Phase 2 — Independent Components
**Barrier:** All standalone components must compile in isolation before Phase 3.

| Task ID | Agent | Description | Depends On | Output Artifacts |
|---|---|---|---|---|
| T2.1 | agent-whisper | Implement whisper_jni.cpp with `nativeInit`, `nativeTranscribe`, `nativeFree`, `nativeIsLoaded`. Write CMakeLists.txt for ARM64 NDK build linking whisper.cpp | T1.2 | `jni/whisper_jni.cpp`, `jni/CMakeLists.txt` |
| T2.2 | agent-voice | Implement AudioCapture: AudioRecord wrapper for PCM float32 16kHz mono, start/stop recording, buffer management, 30s max duration | T1.2 | `voice/AudioCapture.java` |
| T2.3 | agent-ui | Implement TranscriptionOverlay: FrameLayout with 4 visual states (Recording, Transcribing, ShowingResult, Editing), send/cancel buttons. Implement layout XML. | T1.2 | `voice/TranscriptionOverlay.java`, `res/layout/transcription_overlay.xml` |
| T2.4 | agent-ui | Create quest_defaults.xml: font size 20sp, extra key sizing 150%, PTT button config | T1.1 | `res/values/quest_defaults.xml` |
| T2.5 | agent-build | Create GitHub Actions CI workflow: build APK for ARM64 on push | T1.1 | `.github/workflows/build-arm64.yml` |

### Phase 3 — Core Systems
**Barrier:** VoiceInputManager + WhisperBridge + QuestInputHandler must be complete before integration.

| Task ID | Agent | Description | Depends On | Output Artifacts |
|---|---|---|---|---|
| T3.1 | agent-voice | Implement VoiceInputManager: full state machine (Idle→Recording→Transcribing→ShowingResult→Editing→Error→Idle), orchestrates AudioCapture + WhisperBridge + TranscriptionOverlay via callbacks | T2.2, T2.3 | `voice/VoiceInputManager.java` |
| T3.2 | agent-voice | Implement WhisperBridge: Java wrapper loading native lib, calling JNI methods, model lifecycle (init from assets, transcribe on worker thread, free on destroy) | T2.1 | `voice/WhisperBridge.java` |
| T3.3 | agent-ui | Implement QuestInputHandler: map Quest controller buttons (A/X) to PTT events, thumbstick to scroll, B/Y to back. Configurable keycode mapping. | T1.2 | `input/QuestInputHandler.java` |
| T3.4 | agent-ui | Implement OnboardingFlow: first-launch dialog, automated `pkg update && pkg install nodejs git`, Claude Code install command display, mic test, skippable steps | T1.2, T2.4 | `onboarding/OnboardingFlow.java`, `res/layout/onboarding_dialog.xml` |

### Phase 4 — Activity Integration
**Barrier:** TermuxActivity must compile with all components wired. This is the critical integration phase.

| Task ID | Agent | Description | Depends On | Output Artifacts |
|---|---|---|---|---|
| T4.1 | agent-integration | Modify TermuxActivity: instantiate VoiceInputManager, add TranscriptionOverlay to layout hierarchy, wire QuestInputHandler, trigger OnboardingFlow on first launch, add PTT button to extra keys | T3.1, T3.2, T3.3, T3.4 | `app/TermuxActivity.java` (modified) |
| T4.2 | agent-integration | Modify TermuxTerminalSessionClient: add hook for voice text injection into PTY via `VoiceInputCallback` | T3.1 | `app/TermuxTerminalSessionClient.java` (modified) |
| T4.3 | agent-integration | Modify terminal-view: change default font size to 20sp, read from quest_defaults | T2.4 | `terminal-view/` (modified) |
| T4.4 | agent-integration | Configure extra keys row: add mic button (🎤), increase button sizes per quest_defaults, wire mic button to VoiceInputManager | T3.1, T2.4 | Extra keys config (modified) |

### Phase 5 — Documentation & Polish
**Barrier:** All docs and UI polish complete before final validation.

| Task ID | Agent | Description | Depends On | Output Artifacts |
|---|---|---|---|---|
| T5.1 | agent-integration | Write BUILDING.md: fork setup, Gradle build, NDK requirements, APK signing | T4.1 | `docs/BUILDING.md` |
| T5.2 | agent-integration | Write QUEST_SETUP.md: sideloading, phantom process killing ADB commands, BT keyboard pairing | T4.1 | `docs/QUEST_SETUP.md` |
| T5.3 | agent-integration | Write CLAUDE_CODE_SETUP.md: pkg install nodejs, npm install, API key config, first run | T4.1 | `docs/CLAUDE_CODE_SETUP.md` |
| T5.4 | agent-integration | Write README.md, CHANGELOG.md, CONTRIBUTING.md, LICENSE (GPL-3.0), issue templates | T4.1 | Root repo files, `.github/ISSUE_TEMPLATE/*` |
| T5.5 | agent-ui | UI polish: verify overlay animations, adjust TranscriptionOverlay styling for Quest readability, verify QuestInputHandler responsiveness | T4.1 | Updated UI files |

### Phase 6 — Validation & Release
**Barrier:** Final gate before v1.0 tag.

| Task ID | Agent | Description | Depends On | Output Artifacts |
|---|---|---|---|---|
| T6.1 | agent-integration | E2E validation checklist: verify all US-01 through US-04 acceptance criteria against implemented code, document any gaps | T5.1, T5.2, T5.3, T5.4, T5.5 | Validation report |
| T6.2 | agent-build | Release preparation: verify CI produces signed APK, version bumps, CHANGELOG entry for v1.0 | T6.1 | Release-ready state |

---

## 5. Interface Contracts

### 5.1 VoiceState (enum)
**File:** `app/src/main/java/com/voidterm/contracts/VoiceState.java`
**Producer:** agent-build (Phase 1)
**Consumers:** agent-voice, agent-ui, agent-integration

```java
// Intent — exact implementation by agent-build
public enum VoiceState {
    IDLE,
    RECORDING,
    TRANSCRIBING,
    SHOWING_RESULT,
    EDITING,
    ERROR
}
```

### 5.2 VoiceInputCallback (interface)
**File:** `app/src/main/java/com/voidterm/contracts/VoiceInputCallback.java`
**Producer:** agent-build (Phase 1)
**Consumers:** agent-voice (implements trigger), agent-integration (implements injection)

```java
// Intent — voice system calls this to inject text into terminal PTY
public interface VoiceInputCallback {
    void onVoiceTextReady(String text);
    void onVoiceStateChanged(VoiceState newState);
}
```

### 5.3 TranscriptionListener (interface)
**File:** `app/src/main/java/com/voidterm/contracts/TranscriptionListener.java`
**Producer:** agent-build (Phase 1)
**Consumers:** agent-ui (TranscriptionOverlay fires events), agent-voice (VoiceInputManager listens)

```java
// Intent — overlay notifies voice manager of user actions
public interface TranscriptionListener {
    void onSendRequested(String text);
    void onCancelRequested();
    void onEditStarted();
}
```

### 5.4 JNI Function Signatures
**File:** `app/src/main/jni/whisper_jni.cpp`
**Producer:** agent-whisper (Phase 2)
**Consumer:** agent-voice (WhisperBridge.java wraps these)

| JNI Method | Java Signature | Description |
|---|---|---|
| `Java_com_voidterm_voice_WhisperBridge_nativeInit` | `(String modelPath) → long` | Load model, return context handle |
| `Java_com_voidterm_voice_WhisperBridge_nativeTranscribe` | `(long ctx, float[] audio, String lang) → String` | Transcribe PCM buffer |
| `Java_com_voidterm_voice_WhisperBridge_nativeFree` | `(long ctx) → void` | Release model |
| `Java_com_voidterm_voice_WhisperBridge_nativeIsLoaded` | `(long ctx) → boolean` | Check state |

### 5.5 VoiceInputManager Public API
**File:** `app/src/main/java/com/voidterm/voice/VoiceInputManager.java`
**Producer:** agent-voice (Phase 3)
**Consumer:** agent-integration (TermuxActivity wires this)

```java
// Intent — public API that TermuxActivity uses
public class VoiceInputManager {
    VoiceInputManager(Context context, TranscriptionOverlay overlay, VoiceInputCallback callback);
    void onPushToTalkPressed();
    void onPushToTalkReleased();
    void onDoubleTap(); // cancel recording
    VoiceState getCurrentState();
    void destroy(); // cleanup
}
```

### 5.6 TranscriptionOverlay Public API
**File:** `app/src/main/java/com/voidterm/voice/TranscriptionOverlay.java`
**Producer:** agent-ui (Phase 2)
**Consumer:** agent-voice (VoiceInputManager drives state), agent-integration (adds to layout)

```java
// Intent — overlay driven by VoiceInputManager
public class TranscriptionOverlay extends FrameLayout {
    TranscriptionOverlay(Context context);
    void setState(VoiceState state);
    void showTranscription(String text);
    void setTranscriptionListener(TranscriptionListener listener);
    void setVolumeLevel(float level); // 0.0-1.0, for recording visualization
}
```

### 5.7 QuestInputHandler Public API
**File:** `app/src/main/java/com/voidterm/input/QuestInputHandler.java`
**Producer:** agent-ui (Phase 3)
**Consumer:** agent-integration (TermuxActivity delegates key events)

```java
// Intent — maps Quest controller buttons to actions
public class QuestInputHandler {
    QuestInputHandler(VoiceInputManager voiceManager);
    boolean onKeyDown(int keyCode, KeyEvent event); // returns true if consumed
    boolean onKeyUp(int keyCode, KeyEvent event);
    void setPttKeyCode(int keyCode); // configurable
}
```

### 5.8 OnboardingFlow Public API
**File:** `app/src/main/java/com/voidterm/onboarding/OnboardingFlow.java`
**Producer:** agent-ui (Phase 3)
**Consumer:** agent-integration (TermuxActivity triggers on first launch)

```java
// Intent — first-launch wizard
public class OnboardingFlow {
    OnboardingFlow(Activity activity);
    void showIfFirstLaunch(); // checks SharedPreferences
    void setOnCompleteListener(Runnable listener);
}
```

---

## 6. Critical Path

```
T1.2 → T2.1 → T3.2 ─┐
                       ├──→ T3.1 → T4.1 → T5.x → T6.1 → T6.2
T1.2 → T2.2 ──────────┤
T1.2 → T2.3 ──────────┘
```

**Critical chain:** Contracts → whisper JNI → WhisperBridge → VoiceInputManager → TermuxActivity integration → Docs → Validation → Release

**Highest risk:** T2.1 (whisper.cpp ARM64 compilation) — if NDK build fails, the entire voice pipeline is blocked. Mitigation: agent-whisper starts immediately in Phase 2 and has no other tasks.

---

## 7. Phase Gates

| Phase | Gate Condition |
|---|---|
| 1 → 2 | All contract .java files exist and are syntactically valid |
| 2 → 3 | whisper_jni.cpp exports declared functions; AudioCapture compiles; TranscriptionOverlay inflates |
| 3 → 4 | VoiceInputManager state machine is complete; WhisperBridge loads native lib; QuestInputHandler handles key events; OnboardingFlow shows dialog |
| 4 → 5 | TermuxActivity compiles with all components instantiated; PTT triggers full voice pipeline; text injects into PTY |
| 5 → 6 | All docs exist; UI polish reviewed |
| 6 → release | All US-01 through US-04 acceptance criteria verified |
