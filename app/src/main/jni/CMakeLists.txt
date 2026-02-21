cmake_minimum_required(VERSION 3.10)
project(whisper_jni)

# whisper.cpp build options for Android ARM64
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_NO_ACCELERATE ON CACHE BOOL "" FORCE)
set(WHISPER_NO_METAL ON CACHE BOOL "" FORCE)
set(WHISPER_NO_CUDA ON CACHE BOOL "" FORCE)
set(WHISPER_NO_OPENCL ON CACHE BOOL "" FORCE)

# Enable ARM NEON SIMD optimizations
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    add_compile_options(-march=armv8-a+simd)
endif()

# Add whisper.cpp as subdirectory
add_subdirectory(whisper.cpp)

# Create the JNI shared library
add_library(whisper_jni SHARED whisper_jni.cpp)

# Link against whisper and Android log
target_link_libraries(whisper_jni
    whisper
    log
)

# Include whisper.cpp headers
target_include_directories(whisper_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp
)

# LD_PRELOAD library that remaps /data/data/com.termux/ -> /data/data/com.voidterm/
# in file-access functions (open, stat, opendir, etc.)
add_library(voidterm-remap SHARED voidterm_remap.c)
target_link_libraries(voidterm-remap dl)
