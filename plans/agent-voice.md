# Agent: agent-voice

**Role:** Java voice input system — audio capture, Whisper wrapper, state machine
**Phases active:** 2, 3

---

## File Ownership

| Permission | Path |
|---|---|
| WRITE | `app/src/main/java/com/voidterm/voice/AudioCapture.java` |
| WRITE | `app/src/main/java/com/voidterm/voice/WhisperBridge.java` |
| WRITE | `app/src/main/java/com/voidterm/voice/VoiceInputManager.java` |
| READ | `app/src/main/java/com/voidterm/contracts/*.java` |
| READ | `app/src/main/jni/whisper_jni.cpp` (JNI signatures) |
| READ | `app/src/main/java/com/voidterm/voice/TranscriptionOverlay.java` (public API) |
| READ | All |

---

## Phase 2 — Independent Components

### Phase Gate (entry)
- T1.2 complete (contracts exist)

### Task T2.2 — AudioCapture

- [ ] **T2.2**

**Description:** Implement the audio recording component that captures microphone input during Push-to-Talk and delivers a float[] PCM buffer to whisper.cpp.

**Key behaviors:**
- Uses `AudioRecord` with `AudioFormat.ENCODING_PCM_FLOAT`, 16000 Hz, mono
- Audio source: `MediaRecorder.AudioSource.MIC`
- Runs on a dedicated thread (not main thread)
- `startRecording()` — begins capture, accumulates samples in a growing buffer
- `stopRecording()` — stops capture, returns accumulated `float[]` PCM data
- `getVolumeLevel()` — returns current RMS volume (0.0-1.0) for visualization during recording
- Enforces 30-second max duration — auto-stops after 30s
- Cleans up AudioRecord on `release()`
- Thread-safe start/stop (guards against double-start or stop-before-start)

**Audio parameters (from specs 4.2):**
- Format: float32 PCM
- Sample rate: 16,000 Hz
- Channels: Mono
- Max duration: 30 seconds
- Buffer: pre-allocated for 30s = 480,000 floats = ~1.9 MB

**Input dependencies:** T1.2 (contracts — VoiceState used in error reporting)
**Output artifacts:**
- `app/src/main/java/com/voidterm/voice/AudioCapture.java`

**Acceptance criteria:**
- Uses `ENCODING_PCM_FLOAT` (not PCM_16BIT — whisper.cpp expects float32)
- Sample rate is exactly 16000 Hz
- Records in mono
- 30s max enforced
- `getVolumeLevel()` returns normalized RMS
- Thread-safe start/stop with guards
- `release()` frees AudioRecord
- No audio data leaks (buffer cleared after consumption)

---

## Phase 3 — Core Systems

### Phase Gate (entry)
- T2.1 complete (whisper_jni.cpp exists — needed for JNI function signatures)
- T2.2 complete (AudioCapture exists — own output from Phase 2)
- T2.3 complete (TranscriptionOverlay exists — needed for state driving)

### Task T3.2 — WhisperBridge

- [ ] **T3.2**

**Description:** Java wrapper around the native JNI layer. Handles model lifecycle and provides a clean async API for transcription.

**Key behaviors:**
- `loadModel(Context context, String modelName)` — copies model from assets to internal storage on first run, then calls `nativeInit` with the file path. Runs on background thread.
- `transcribe(float[] audio, String language, Callback callback)` — runs `nativeTranscribe` on a background thread, delivers result via callback on main thread
- `isModelLoaded()` — checks via `nativeIsLoaded`
- `release()` — calls `nativeFree`, nulls context handle
- Loads native library: `System.loadLibrary("whisper_jni")`

**Model asset handling:**
- Model files live in `assets/models/` (e.g., `ggml-base.bin`)
- On first load, copy from assets to `context.getFilesDir() + "/models/"` (assets can't be read as file paths by whisper.cpp)
- Skip copy if file already exists and sizes match

**Threading:**
- Model loading: background thread, callback on completion
- Transcription: background thread (whisper inference is 2-4s), result callback on main thread via `Handler(Looper.getMainLooper())`
- Only one transcription at a time (reject concurrent calls)

**Input dependencies:**
- T2.1 (whisper_jni.cpp — JNI function names must match exactly)

**Output artifacts:**
- `app/src/main/java/com/voidterm/voice/WhisperBridge.java`

**Acceptance criteria:**
- Native method declarations match JNI signatures from T2.1 exactly
- `System.loadLibrary("whisper_jni")` in static block
- Model copied from assets to internal storage on first use
- Transcription runs off main thread
- Result delivered on main thread
- Concurrent transcription calls rejected
- `release()` frees native resources
- Handles model load failure gracefully (callback with error)

---

### Task T3.1 — VoiceInputManager

- [ ] **T3.1**

**Description:** The central orchestrator — implements the full state machine from specs section 4.5. Coordinates AudioCapture, WhisperBridge, and TranscriptionOverlay.

**State machine (from specs):**
```
[*] → Idle
Idle → Recording : PTT pressed
Recording → Transcribing : PTT released
Recording → Idle : Double-tap (cancel)
Transcribing → ShowingResult : Text ready
Transcribing → Error : Failure
ShowingResult → Idle : Enter (inject into terminal)
ShowingResult → Editing : User modifies text
ShowingResult → Idle : Escape (cancel)
Editing → Idle : Enter (inject modified text)
Error → Idle : Dismiss
```

**Key behaviors:**
- Constructor takes `Context`, `TranscriptionOverlay`, `VoiceInputCallback`
- `onPushToTalkPressed()` — transitions from IDLE to RECORDING, starts AudioCapture, updates overlay
- `onPushToTalkReleased()` — transitions to TRANSCRIBING, stops AudioCapture, sends PCM to WhisperBridge
- `onDoubleTap()` — cancels recording, returns to IDLE
- Implements `TranscriptionListener` to receive overlay events (Send, Cancel, Edit)
- On Send: calls `VoiceInputCallback.onVoiceTextReady(text)`, transitions to IDLE
- On Cancel: transitions to IDLE, clears overlay
- On state change: calls `VoiceInputCallback.onVoiceStateChanged(state)`, updates overlay state
- `getCurrentState()` — returns current VoiceState
- `destroy()` — releases AudioCapture, WhisperBridge

**Volume visualization:**
- During RECORDING state, periodically poll `AudioCapture.getVolumeLevel()` and call `TranscriptionOverlay.setVolumeLevel()` (use Handler with 100ms interval)

**Error handling:**
- If WhisperBridge fails: transition to ERROR state, show error in overlay
- If AudioCapture fails to start: transition to ERROR, show error
- Error auto-dismisses after 3 seconds

**Input dependencies:**
- T2.2 (AudioCapture — instantiated and controlled by this manager)
- T2.3 (TranscriptionOverlay — driven by this manager via `setState()` and `showTranscription()`)
- T3.2 (WhisperBridge — called for transcription)
- T1.2 (contracts — VoiceState, VoiceInputCallback, TranscriptionListener)

**Output artifacts:**
- `app/src/main/java/com/voidterm/voice/VoiceInputManager.java`

**Acceptance criteria:**
- All 10 state transitions from specs section 4.5 implemented
- No state can be skipped (e.g., can't go from IDLE to TRANSCRIBING)
- PTT pressed in non-IDLE state is ignored
- Implements `TranscriptionListener` (receives overlay events)
- Calls `VoiceInputCallback` on text ready and state changes
- Volume polling during RECORDING at ~100ms interval
- Audio buffers released after transcription completes
- Error state auto-dismisses after 3 seconds
- `destroy()` cleans up all resources
- Thread-safe state transitions (synchronized or atomic)
