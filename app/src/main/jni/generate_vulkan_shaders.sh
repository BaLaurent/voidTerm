#!/usr/bin/env bash
#
# Pre-generates Vulkan shaders for cross-compilation to ARM64 (Android NDK).
#
# Problem: whisper.cpp's GGML_VULKAN=ON builds vulkan-shaders-gen for the
# target arch (ARM64), but it must run on the host (x86_64). The NDK also
# lacks vulkan.hpp (C++ wrapper headers).
#
# Solution: Build vulkan-shaders-gen natively on the host, run it, and copy
# the Vulkan C++ headers. Our CMakeLists.txt then integrates these pre-generated
# files without enabling GGML_VULKAN in whisper.cpp's build system.
#
# Host prerequisites: glslc, g++, vulkan-headers (vulkan.hpp + vk_video/)
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WHISPER_DIR="${SCRIPT_DIR}/whisper.cpp"
SHADERS_SRC="${WHISPER_DIR}/ggml/src/ggml-vulkan/vulkan-shaders"
OUTPUT_DIR="${SCRIPT_DIR}/generated"
BUILD_DIR="${OUTPUT_DIR}/_build_host"

SHADER_OUT="${OUTPUT_DIR}/vulkan-shaders"
HEADERS_OUT="${OUTPUT_DIR}/vulkan-headers"

# Detect glslc
GLSLC="$(command -v glslc 2>/dev/null || true)"
if [ -z "${GLSLC}" ]; then
    echo "ERROR: glslc not found. Install vulkan-tools or the Vulkan SDK." >&2
    exit 1
fi

# Detect host Vulkan headers
VULKAN_HPP=""
for dir in /usr/include /usr/local/include; do
    if [ -f "${dir}/vulkan/vulkan.hpp" ]; then
        VULKAN_HPP="${dir}"
        break
    fi
done
if [ -z "${VULKAN_HPP}" ]; then
    echo "ERROR: vulkan.hpp not found. Install vulkan-headers." >&2
    exit 1
fi

echo "=== VoidTerm Vulkan Shader Generation ==="
echo "glslc:          ${GLSLC}"
echo "Vulkan headers: ${VULKAN_HPP}"
echo "Output:         ${OUTPUT_DIR}"
echo ""

# ── Step 1: Build vulkan-shaders-gen natively on host ──
# Single C++17 file, only needs vulkan_core.h (for VkFormat constants) and pthreads.
# We compile directly with g++ instead of CMake because the upstream CMakeLists.txt
# is a sub-module (no cmake_minimum_required/project) and can't be used standalone.

echo "[1/3] Building vulkan-shaders-gen (host native)..."
mkdir -p "${BUILD_DIR}"
SHADERS_GEN="${BUILD_DIR}/vulkan-shaders-gen"
g++ -std=c++17 -O2 -o "${SHADERS_GEN}" \
    "${SHADERS_SRC}/vulkan-shaders-gen.cpp" \
    -I"${VULKAN_HPP}" \
    -lpthread

if [ ! -x "${SHADERS_GEN}" ]; then
    echo "ERROR: vulkan-shaders-gen build failed." >&2
    exit 1
fi

# ── Step 2: Generate shaders ──

echo "[2/3] Generating Vulkan shaders..."
mkdir -p "${SHADER_OUT}/spv"
"${SHADERS_GEN}" \
    --glslc "${GLSLC}" \
    --input-dir "${SHADERS_SRC}" \
    --output-dir "${SHADER_OUT}/spv" \
    --target-hpp "${SHADER_OUT}/ggml-vulkan-shaders.hpp" \
    --target-cpp "${SHADER_OUT}/ggml-vulkan-shaders.cpp" \
    --no-clean

if [ ! -f "${SHADER_OUT}/ggml-vulkan-shaders.cpp" ]; then
    echo "ERROR: Shader generation failed." >&2
    exit 1
fi

# ── Step 3: Copy Vulkan C++ headers from host ──

echo "[3/3] Copying Vulkan C++ headers..."
mkdir -p "${HEADERS_OUT}"
cp -r "${VULKAN_HPP}/vulkan" "${HEADERS_OUT}/"
if [ -d "${VULKAN_HPP}/vk_video" ]; then
    cp -r "${VULKAN_HPP}/vk_video" "${HEADERS_OUT}/"
fi

# Clean build dir
rm -rf "${BUILD_DIR}"

echo ""
echo "=== Done ==="
echo "Generated files:"
echo "  ${SHADER_OUT}/ggml-vulkan-shaders.hpp"
echo "  ${SHADER_OUT}/ggml-vulkan-shaders.cpp"
echo "  ${HEADERS_OUT}/vulkan/ (C++ headers)"
ls -lh "${SHADER_OUT}/ggml-vulkan-shaders.cpp"
