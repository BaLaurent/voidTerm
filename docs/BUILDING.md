# Building VoidTerm from Source

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 17 | OpenJDK or Oracle JDK |
| Android SDK | API 34 | Install via Android Studio or `sdkmanager` |
| Android NDK | r25+ (25.2.9519653) | Required for whisper.cpp ARM64 compilation |
| CMake | 3.22.1+ | Install via SDK Manager or system package manager |
| Git | 2.x | With submodule support |

Ensure `ANDROID_HOME` and `ANDROID_NDK_HOME` are set:

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/25.2.9519653
```

## Clone

```bash
git clone --recursive https://github.com/BaLaurent/voidTerm.git
cd voidTerm
```

The `--recursive` flag is required to pull the whisper.cpp submodule located at `app/src/main/jni/whisper.cpp`.

If you already cloned without `--recursive`:

```bash
git submodule update --init --recursive
```

## Build

```bash
./gradlew assembleRelease
```

The unsigned APK is generated at:

```
app/build/outputs/apk/release/app-release-unsigned.apk
```

### Debug build

```bash
./gradlew assembleDebug
```

Debug APK at: `app/build/outputs/apk/debug/app-debug.apk`

## Signing

Sign the release APK using standard Android signing:

```bash
# Generate a keystore (first time only)
keytool -genkey -v -keystore voidterm.keystore -alias voidterm \
  -keyalg RSA -keysize 2048 -validity 10000

# Sign the APK
apksigner sign --ks voidterm.keystore \
  --ks-key-alias voidterm \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

## Architecture

VoidTerm builds for **ARM64 only** (`arm64-v8a`). This is the only architecture supported by Meta Quest devices. The NDK compiles whisper.cpp with ARM64 NEON SIMD optimizations for optimal inference performance.

## Project Structure

```
voidterm/
├── app/                      # Main application module
│   └── src/main/
│       ├── java/com/voidterm/ # Java sources
│       ├── jni/              # Native code
│       │   ├── whisper_jni.cpp
│       │   ├── CMakeLists.txt
│       │   └── whisper.cpp/  # Git submodule
│       └── res/              # Android resources
├── terminal-emulator/        # Terminal emulation library
├── terminal-view/            # Terminal rendering (Android View)
├── termux-shared/            # Shared utilities
└── assets/models/            # Whisper model files
    ├── ggml-base.bin         # 142 MB (default)
    └── ggml-tiny.bin         # 75 MB (lightweight)
```

## Troubleshooting

### NDK version mismatch

```
NDK not configured. Download it with SDK manager.
```

Ensure NDK r25+ (25.2.9519653) is installed. Verify with:

```bash
ls $ANDROID_HOME/ndk/
```

If a different version is installed, either install 25.2.9519653 via SDK Manager or update `app/build.gradle` to reference your installed version.

### CMake errors

```
CMake was not found in SDK
```

Install CMake 3.22.1+ via Android Studio SDK Manager (SDK Tools tab) or:

```bash
sdkmanager "cmake;3.22.1"
```

### whisper.cpp submodule missing

```
CMake Error: Could not find whisper.cpp sources
```

The submodule was not initialized. Run:

```bash
git submodule update --init --recursive
```

Verify the directory is populated:

```bash
ls app/src/main/jni/whisper.cpp/
```

### Gradle build failures

If dependencies fail to resolve, check your internet connection and proxy settings. Clean and retry:

```bash
./gradlew clean && ./gradlew assembleRelease
```

### Out of memory during build

whisper.cpp compilation requires significant memory. Increase Gradle JVM heap in `gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx4g
```
