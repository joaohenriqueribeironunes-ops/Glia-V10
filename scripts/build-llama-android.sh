#!/usr/bin/env bash
# Compila o llama.cpp (upstream) para Android arm64 e copia as .so para o módulo ai.
#
# Requisitos: Android SDK com NDK 27 e CMake 3.31, git.
# O llama.cpp é clonado em third_party/ (fora do git) e não é redistribuído no
# repositório; as bibliotecas resultantes ficam em ai/src/main/jniLibs/arm64-v8a/.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${LOCALAPPDATA:-$HOME}/Android/Sdk}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/27.0.12077973}"
CMAKE="$SDK/cmake/3.31.6/bin/cmake.exe"
NINJA="$SDK/cmake/3.31.6/bin/ninja.exe"
[ -x "$CMAKE" ] || CMAKE="$(command -v cmake)"
[ -x "$NINJA" ] || NINJA="$(command -v ninja)"

if [ ! -f "$ROOT/third_party/llama.cpp/include/llama.h" ]; then
  git clone --depth 1 https://github.com/ggml-org/llama.cpp "$ROOT/third_party/llama.cpp"
fi

"$CMAKE" -S "$ROOT/third_party/llama.cpp" -B "$ROOT/.build/llama-arm64" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF \
  -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_CURL=OFF \
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF

"$CMAKE" --build "$ROOT/.build/llama-arm64" --target llama -j 8

DEST="$ROOT/ai/src/main/jniLibs/arm64-v8a"
mkdir -p "$DEST"
cp "$ROOT/.build/llama-arm64/bin/libllama.so" \
   "$ROOT/.build/llama-arm64/bin/libggml.so" \
   "$ROOT/.build/llama-arm64/bin/libggml-base.so" \
   "$ROOT/.build/llama-arm64/bin/libggml-cpu.so" "$DEST/"

STRIP="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe"
[ -x "$STRIP" ] || STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
[ -x "$STRIP" ] || STRIP="$(command -v llvm-strip || true)"
[ -n "$STRIP" ] && [ -x "$STRIP" ] && "$STRIP" --strip-unneeded "$DEST"/*.so

echo "pronto: $DEST"
ls -l "$DEST"
