#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT_DIR/android"
JNI_LIBS_DIR="$ANDROID_DIR/app/src/main/jniLibs"
APK_RELATIVE_PATH="app/build/outputs/apk/debug/app-debug.apk"
PROBE_APK_RELATIVE_PATH="probe/build/outputs/apk/debug/probe-debug.apk"
APK_OUT_DIR="${APK_OUT_DIR:-}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-}"

if [[ -f "$ROOT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT_DIR/.env"
  set +a
fi

export IODINE_ANDROID_DEFAULT_DOMAIN="${IODINE_ANDROID_DEFAULT_DOMAIN:-}"
export IODINE_ANDROID_DEFAULT_PASSWORD="${IODINE_ANDROID_DEFAULT_PASSWORD:-}"

"$ROOT_DIR/scripts/build-android-native.sh"

mkdir -p "$JNI_LIBS_DIR/arm64-v8a" "$JNI_LIBS_DIR/armeabi-v7a"

cp "$ROOT_DIR/src/libs/arm64-v8a/libiodine_android.so" "$JNI_LIBS_DIR/arm64-v8a/libiodine_android.so"
cp "$ROOT_DIR/src/libs/armeabi-v7a/libiodine_android.so" "$JNI_LIBS_DIR/armeabi-v7a/libiodine_android.so"

chmod 0644 "$JNI_LIBS_DIR/arm64-v8a/libiodine_android.so" "$JNI_LIBS_DIR/armeabi-v7a/libiodine_android.so"

cd "$ANDROID_DIR"

if [[ -n "$ANDROID_SDK_ROOT" ]]; then
  printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > local.properties
fi

gradle --no-daemon assembleDebug

if [[ -n "$APK_OUT_DIR" ]]; then
  mkdir -p "$APK_OUT_DIR"
  cp "$ANDROID_DIR/$APK_RELATIVE_PATH" "$APK_OUT_DIR/app-debug.apk"
  cp "$ANDROID_DIR/$PROBE_APK_RELATIVE_PATH" "$APK_OUT_DIR/probe-debug.apk"
fi
