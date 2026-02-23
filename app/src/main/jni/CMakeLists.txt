cmake_minimum_required(VERSION 3.10)
project(whisper_jni)

set(CMAKE_CXX_STANDARD 17)

# ── Source file compilation (matches official whisper.android examples) ──
# The official examples compile ggml/whisper sources DIRECTLY into one .so,
# bypassing the ggml CMake build system entirely. This avoids the backend
# dispatch infrastructure (GGML_BACKEND_SHARED, GGML_USE_CPU defines, etc.)
# which caused ~100x compute slowdown on Quest 2/XR2.
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)
set(GGML_SOURCES
    ${WHISPER_DIR}/ggml/src/ggml.c
    ${WHISPER_DIR}/ggml/src/ggml-alloc.c
    ${WHISPER_DIR}/ggml/src/ggml-backend.cpp
    ${WHISPER_DIR}/ggml/src/ggml-backend-reg.cpp
    ${WHISPER_DIR}/ggml/src/ggml-quants.c
    ${WHISPER_DIR}/ggml/src/ggml-threading.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu.c
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu-aarch64.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu-hbm.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu-quants.c
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu-traits.cpp
)

# NOTE: Vulkan backend (ggml-vulkan.cpp) is NOT compiled.
# ggml-vulkan.cpp calls exit(1) on any Vulkan error, which kills the whole
# Android process. It also conflicts with Android's HWUI RenderThread Vulkan
# usage, causing SIGSEGV in the UI render thread. Until whisper.cpp upstream
# handles Vulkan errors gracefully on Android, we stay CPU-only.

set(WHISPER_SOURCES
    ${WHISPER_DIR}/src/whisper.cpp
)

find_library(LOG_LIB log)

# Function to create a whisper JNI library variant.
# Each variant compiles all sources directly into one .so (no ggml CMake system).
function(build_whisper_jni TARGET_NAME)
    add_library(${TARGET_NAME} SHARED
        whisper_jni.cpp
        ${GGML_SOURCES}
        ${WHISPER_SOURCES}
    )

    target_link_libraries(${TARGET_NAME} ${LOG_LIB} android)

    # Register the CPU backend in ggml-backend-reg.cpp (required for optimized compute).
    # Without this, ggml reports "backends (0):" and uses a slow legacy fallback path.
    target_compile_definitions(${TARGET_NAME} PRIVATE GGML_USE_CPU)

    target_include_directories(${TARGET_NAME} PRIVATE
        ${WHISPER_DIR}
        ${WHISPER_DIR}/src
        ${WHISPER_DIR}/include
        ${WHISPER_DIR}/ggml/include
        ${WHISPER_DIR}/ggml/src
        ${WHISPER_DIR}/ggml/src/ggml-cpu
    )

    # ALWAYS optimize ggml/whisper — even in Debug builds.
    # Without -O3, NEON intrinsics and matmul kernels are ~15-20x slower (-O0 vs -O3).
    # This is the root cause of the 63s transcription on Quest 2 (expected: ~3-5s).
    target_compile_options(${TARGET_NAME} PRIVATE
        -O3
        -Wall
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        -ffunction-sections
        -fdata-sections
    )
    target_link_options(${TARGET_NAME} PRIVATE
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
    )

    # Apply any extra compile flags passed as ARGN
    if(ARGN)
        target_compile_options(${TARGET_NAME} PRIVATE ${ARGN})
    endif()
endfunction()

# Default build: ARM NEON (armv8-a baseline, NEON is always available on arm64)
build_whisper_jni(whisper_jni)

# FP16 build: ARMv8.2-A half-precision (~15-30% faster on Quest XR2)
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_whisper_jni(whisper_jni_v8fp16 -march=armv8.2-a+fp16)
endif()

# LD_PRELOAD library that remaps /data/data/com.termux/ -> /data/data/com.voidterm/
# in file-access functions (open, stat, opendir, etc.)
add_library(voidterm-remap SHARED voidterm_remap.c)
target_link_libraries(voidterm-remap dl)
