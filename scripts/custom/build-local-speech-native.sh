#!/bin/sh
# Build an alternative GGUF JNI artifact; never overwrite the checked-in runtime.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to your accepted, installed Android NDK}"
TOOLCHAIN="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
if [ ! -f "$TOOLCHAIN" ]; then
  printf '%s\n' 'Android NDK toolchain file not found.' >&2
  exit 1
fi
BUILD_DIR=${ETA_NATIVE_BUILD_DIR:-"$ROOT/.native-build/local-speech"}
STL=${ETA_ANDROID_STL:-c++_shared}
case "$STL" in c++_shared|c++_static) ;; *) printf '%s\n' 'Unsupported Android STL choice.' >&2; exit 1;; esac
cmake -S "$ROOT/app/src/main/cpp/local-speech" -B "$BUILD_DIR" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-33 \
  -DANDROID_STL="$STL" -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_FLAGS_RELEASE='-O3 -DNDEBUG' \
  -DCMAKE_CXX_FLAGS_RELEASE='-O3 -DNDEBUG'
cmake --build "$BUILD_DIR" --target eta_transcribe_jni --parallel "${ETA_NATIVE_JOBS:-3}"
printf '\n%s\n' 'Native build finished. Output candidates:'
find "$BUILD_DIR" -type f -name 'libeta_transcribe_jni.so' -print
printf '%s\n' 'No checked-in .so was replaced. Verify ELF/JNI/STL and inference before packaging.'
