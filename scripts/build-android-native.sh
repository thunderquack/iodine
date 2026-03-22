#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="$ROOT_DIR/src"

export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:?ANDROID_NDK_ROOT is required}"

cd "$SRC_DIR"

make base64u.c

rm -rf libs obj

"$ANDROID_NDK_ROOT/ndk-build" \
  NDK_PROJECT_PATH=. \
  APP_BUILD_SCRIPT=Android.16.mk \
  APP_PLATFORM=android-16 \
  APP_ABI="arm64-v8a armeabi-v7a"
