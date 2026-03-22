#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT_DIR/android"
ASSETS_DIR="$ANDROID_DIR/app/src/main/assets/bin"
APK_RELATIVE_PATH="app/build/outputs/apk/debug/app-debug.apk"
APK_OUT_DIR="${APK_OUT_DIR:-}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-}"

"$ROOT_DIR/scripts/build-android-native.sh"

mkdir -p "$ASSETS_DIR/arm64-v8a" "$ASSETS_DIR/armeabi-v7a"

cp "$ROOT_DIR/src/libs/arm64-v8a/iodine" "$ASSETS_DIR/arm64-v8a/iodine"
cp "$ROOT_DIR/src/libs/armeabi-v7a/iodine" "$ASSETS_DIR/armeabi-v7a/iodine"

chmod 0644 "$ASSETS_DIR/arm64-v8a/iodine" "$ASSETS_DIR/armeabi-v7a/iodine"

cd "$ANDROID_DIR"

if [[ -n "$ANDROID_SDK_ROOT" ]]; then
  printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > local.properties
fi

gradle --no-daemon assembleDebug

if [[ -n "$APK_OUT_DIR" ]]; then
  mkdir -p "$APK_OUT_DIR"
  cp "$ANDROID_DIR/$APK_RELATIVE_PATH" "$APK_OUT_DIR/app-debug.apk"
fi
