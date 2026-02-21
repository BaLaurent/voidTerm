# Agent: agent-build

**Role:** Project scaffolding, Gradle configuration, shared contracts, CI
**Phases active:** 1, 2, 6

---

## File Ownership

| Permission | Path |
|---|---|
| WRITE | `build.gradle` |
| WRITE | `settings.gradle` |
| WRITE | `gradle.properties` |
| WRITE | `app/build.gradle` |
| WRITE | `app/src/main/AndroidManifest.xml` |
| WRITE | `app/src/main/java/com/voidterm/contracts/VoiceState.java` |
| WRITE | `app/src/main/java/com/voidterm/contracts/VoiceInputCallback.java` |
| WRITE | `app/src/main/java/com/voidterm/contracts/TranscriptionListener.java` |
| WRITE | `.github/workflows/build-arm64.yml` |
| WRITE | `.gitmodules` |
| READ | All |

---

## Phase 1 — Scaffolding & Contracts

### Phase Gate (entry)
None — this agent starts first.

### Task T1.1 — Project Directory Structure & Gradle Configuration

- [ ] **T1.1**

**Description:** Create the full project directory tree matching the specs structure. Set up root `build.gradle` with Android Gradle Plugin, `settings.gradle` including all modules (`:app`, `:terminal-emulator`, `:terminal-view`, `:termux-shared`). Configure `app/build.gradle` with:
- `minSdkVersion 29` (Quest requirement)
- `targetSdkVersion 33`
- `ndk { abiFilters 'arm64-v8a' }`
- CMake external native build pointing to `src/main/jni/CMakeLists.txt`
- Java source sets including the `com.voidterm` package hierarchy

Configure `AndroidManifest.xml` with:
- `android.permission.RECORD_AUDIO`
- `android.permission.INTERNET` (for Claude Code API calls via Node.js)
- Package name: `com.voidterm`

**Input dependencies:** None
**Output artifacts:**
- `build.gradle` — root Gradle build
- `settings.gradle` — module includes
- `gradle.properties` — JVM args, NDK version
- `app/build.gradle` — app module with NDK config
- `app/src/main/AndroidManifest.xml` — permissions
- Directory tree for all packages

**Acceptance criteria:**
- `./gradlew tasks` would list available tasks (conceptual — no Termux source yet)
- Directory structure matches specs section 3.3
- NDK ARM64 build is configured
- Audio permission declared

---

### Task T1.2 — Shared Contracts

- [ ] **T1.2**

**Description:** Create the 3 shared interface files that form the contract between voice, UI, and integration agents. These are intentionally minimal — just enums and callback interfaces, no implementation logic.

**Intent for VoiceState.java:**
- Enum with 6 states: `IDLE`, `RECORDING`, `TRANSCRIBING`, `SHOWING_RESULT`, `EDITING`, `ERROR`
- Maps directly to the state diagram in specs section 4.5

**Intent for VoiceInputCallback.java:**
- Interface with 2 methods:
  - `onVoiceTextReady(String text)` — called when user confirms transcription (Send)
  - `onVoiceStateChanged(VoiceState newState)` — called on every state transition
- Implemented by agent-integration in TermuxTerminalSessionClient (Phase 4)

**Intent for TranscriptionListener.java:**
- Interface with 3 methods:
  - `onSendRequested(String text)` — user tapped Send or pressed Enter
  - `onCancelRequested()` — user tapped Cancel or pressed Escape
  - `onEditStarted()` — user started modifying transcription text
- Fired by TranscriptionOverlay, consumed by VoiceInputManager

**Input dependencies:** None
**Output artifacts:**
- `app/src/main/java/com/voidterm/contracts/VoiceState.java`
- `app/src/main/java/com/voidterm/contracts/VoiceInputCallback.java`
- `app/src/main/java/com/voidterm/contracts/TranscriptionListener.java`

**Acceptance criteria:**
- All 3 files compile independently (no external imports beyond Android SDK)
- VoiceState has exactly 6 values matching specs state diagram
- VoiceInputCallback has exactly 2 methods
- TranscriptionListener has exactly 3 methods
- Package is `com.voidterm.contracts`

---

## Phase 2 — Independent Components

### Phase Gate (entry)
- T1.1 complete (directory structure exists)
- T1.2 complete (contracts exist)

### Task T2.5 — GitHub Actions CI Workflow

- [ ] **T2.5**

**Description:** Create a GitHub Actions workflow that builds the APK for ARM64 on every push to `main` and `develop`, and on PRs. The workflow should:
- Use Ubuntu runner
- Install Android SDK + NDK
- Run `./gradlew assembleRelease`
- Upload APK as artifact
- Cache Gradle dependencies

**Input dependencies:** T1.1 (Gradle config must exist for the build command to reference)
**Output artifacts:**
- `.github/workflows/build-arm64.yml`

**Acceptance criteria:**
- Workflow YAML is valid
- Triggers on push to main/develop and PRs
- Builds ARM64 APK
- Uploads artifact
- Uses Gradle cache

---

## Phase 6 — Validation & Release

### Phase Gate (entry)
- All Phase 5 tasks complete

### Task T6.2 — Release Preparation

- [ ] **T6.2**

**Description:** Verify CI produces a valid APK. Ensure version is set to `1.0.0` in `app/build.gradle`. Verify CHANGELOG.md has a v1.0.0 entry. Ensure `.gitmodules` references whisper.cpp at a stable tag.

**Input dependencies:** T6.1 (validation report confirms readiness)
**Output artifacts:**
- Updated `app/build.gradle` version fields
- Verified `.gitmodules`

**Acceptance criteria:**
- `versionName "1.0.0"` and `versionCode 1` in build.gradle
- CHANGELOG.md documents v1.0.0 features
- whisper.cpp submodule pinned to stable release tag
