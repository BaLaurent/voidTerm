# Phase 1: Fixes critiques - Completion Report

**Completed**: 2026-02-22
**Plan file**: plans/CODE_REVIEW.md

## Tasks Executed

### Task 1: Fix benchmarkTranscribe() use-after-free (C1)
**Status**: ✅ Success
**File**: `WhisperBridge.java:390-401`
**Actions taken**:
- Added `transcribeThread = Thread.currentThread()` before `nativeTranscribe()` call
- Added `transcribeThread = null` in `finally` block (before `isTranscribing.set(false)`)
- This ensures `release()` can `join()` the DeviceProfiler thread if called during benchmark

### Task 2: Fix TOCTOU race in loadModel() (H1/H5)
**Status**: ✅ Success
**File**: `WhisperBridge.java:214-228`
**Actions taken**:
- Added `isTranscribing.get()` re-check inside `synchronized (contextLock)` block
- If transcription started between outer check (line 161) and inner lock, the newly loaded context is freed and load aborted with error callback
- Eliminates the TOCTOU window: `transcribe()` reads `contextHandle` under the same `contextLock`, so if it hasn't read yet, it will get the new handle; if it has, `isTranscribing` is true and we abort

### Task 3: Add ExceptionCheck after CallVoidMethod in JNI (H4)
**Status**: ✅ Success
**File**: `whisper_jni.cpp:67-78`
**Actions taken**:
- Added `ExceptionCheck()` / `ExceptionDescribe()` / `ExceptionClear()` after `CallVoidMethod` in `streaming_segment_callback`
- Prevents undefined behavior if Java throws during the callback (e.g. OOM in `mainHandler.post()`)

### Task 4: Add initializing guard for spinner listeners (H13)
**Status**: ✅ Success
**File**: `SettingsDialog.java`
**Actions taken**:
- Added `final boolean[] initializing = {true}` at start of `show()`
- Wrapped `DeviceProfiler.markUserOverride()` in beamSpinner listener behind `if (!initializing[0])`
- Added `layout.post(() -> initializing[0] = false)` after `dialog.show()` — runs after queued `SelectionNotifier` runnables
- Prevents auto-tuning from being permanently disabled after first Settings open

### Task 5: Make whisperBridge volatile in VoiceInputManager (H3)
**Status**: ✅ Success
**File**: `VoiceInputManager.java:52`
**Actions taken**:
- Changed `private WhisperBridge whisperBridge` to `private volatile WhisperBridge whisperBridge`
- Ensures JMM visibility: pipeline thread always sees the fully constructed object

### Task 6: Fix onScale() hardcoded font size (H7)
**Status**: ✅ Success
**File**: `VoidTermTerminalViewClient.java:50-60`
**Actions taken**:
- Replaced `int currentSize = 20` with `SharedPreferences("voidterm_style").getInt("font_size", 20)`
- Added `stylePrefs.edit().putInt("font_size", newSize).apply()` after `setTextSize()` to persist zoom changes
- Pinch-to-zoom now works correctly for users who changed font size from the default

## Summary
- Tasks completed: 6/6
- Tasks fixed: 0 (all succeeded first try)
- Tasks failed: 0
- Files modified: `WhisperBridge.java`, `whisper_jni.cpp`, `SettingsDialog.java`, `VoiceInputManager.java`, `VoidTermTerminalViewClient.java`
- Build: ✅ assembleDebug passed
