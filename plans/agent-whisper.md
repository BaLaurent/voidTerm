# Agent: agent-whisper

**Role:** whisper.cpp native integration — C++ JNI layer and CMake build configuration
**Phases active:** 2

---

## File Ownership

| Permission | Path |
|---|---|
| WRITE | `app/src/main/jni/whisper_jni.cpp` |
| WRITE | `app/src/main/jni/CMakeLists.txt` |
| WRITE | `app/src/main/jni/whisper.cpp/` (submodule content reference) |
| READ | `app/src/main/java/com/voidterm/contracts/*.java` |
| READ | `app/build.gradle` (NDK config) |
| READ | All |

---

## Phase 2 — Independent Components

### Phase Gate (entry)
- T1.2 complete (contracts exist — needed to understand VoiceState for error reporting)
- T1.1 complete (build.gradle with NDK config exists)

### Task T2.1 — whisper.cpp JNI Native Layer

- [ ] **T2.1**

**Description:** Implement the C++ JNI bridge between Android Java and whisper.cpp. This is the most critical native component — if it fails to compile for ARM64, the entire voice pipeline is blocked.

**JNI Functions to implement:**

1. `Java_com_voidterm_voice_WhisperBridge_nativeInit`
   - Signature: `(JNIEnv*, jobject, jstring modelPath) → jlong`
   - Load whisper model from filesystem path using `whisper_init_from_file()`
   - Return context pointer cast to jlong (handle for subsequent calls)
   - Return 0 on failure

2. `Java_com_voidterm_voice_WhisperBridge_nativeTranscribe`
   - Signature: `(JNIEnv*, jobject, jlong ctx, jfloatArray audio, jstring lang) → jstring`
   - Configure `whisper_full_params` with:
     - `WHISPER_SAMPLING_GREEDY` strategy
     - Language from `lang` parameter (e.g., "en", "fr")
     - Single-threaded initially (Quest thermal constraints)
     - `no_timestamps = true`
   - Call `whisper_full()` with PCM float array
   - Extract text segments and concatenate
   - Return transcribed text as jstring

3. `Java_com_voidterm_voice_WhisperBridge_nativeFree`
   - Signature: `(JNIEnv*, jobject, jlong ctx) → void`
   - Call `whisper_free()` to release model memory

4. `Java_com_voidterm_voice_WhisperBridge_nativeIsLoaded`
   - Signature: `(JNIEnv*, jobject, jlong ctx) → jboolean`
   - Return true if ctx is non-null and valid

**CMakeLists.txt requirements:**
- `cmake_minimum_required(VERSION 3.10)`
- Set `CMAKE_SYSTEM_NAME Android`
- Add whisper.cpp as subdirectory (the submodule at `whisper.cpp/`)
- Enable ARM NEON SIMD: `-DWHISPER_NO_ACCELERATE=ON -DWHISPER_NEON=ON`
- Disable unnecessary features: `-DWHISPER_NO_METAL=ON -DWHISPER_NO_CUDA=ON -DWHISPER_NO_OPENCL=ON`
- Create shared library `whisper_jni` linking against `whisper` and `log`
- Target ABI: `arm64-v8a`

**Input dependencies:**
- T1.2 (contracts, to understand the Java class `WhisperBridge` that will call these JNI functions)
- T1.1 (build.gradle NDK config, for CMake integration path)

**Output artifacts:**
- `app/src/main/jni/whisper_jni.cpp` — complete JNI bridge implementation
- `app/src/main/jni/CMakeLists.txt` — CMake build for ARM64

**Acceptance criteria:**
- JNI function names match Java class `com.voidterm.voice.WhisperBridge` exactly
- CMakeLists.txt targets ARM64 with NEON enabled
- No Metal/CUDA/OpenCL dependencies (Quest has none)
- `nativeTranscribe` handles empty audio gracefully (returns empty string)
- `nativeInit` returns 0 on failure (does not crash)
- `nativeFree` handles null/0 context gracefully
- Code compiles conceptually with Android NDK r25+ and CMake 3.10+
