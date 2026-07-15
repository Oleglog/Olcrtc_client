#!/usr/bin/env bash
set -euo pipefail

readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SOURCE="$ROOT/native/hev-socks5-tunnel/upstream"
readonly JNI="$ROOT/app/src/main/jni/Android.mk"
readonly BRIDGE="$ROOT/app/src/main/jni/hev_jni.c"
readonly GENERATED_BRIDGE="$SOURCE/src/olcrtc-hev-jni.c"
readonly OUTPUT="$ROOT/app/src/main/jniLibs"
readonly REPOSITORY="https://github.com/heiher/hev-socks5-tunnel.git"
readonly TAG="2.15.0"
readonly COMMIT="00c7eb9ad7ca381b0f1fee880abc1077fe9b93be"
readonly REV_ID="${COMMIT:0:12}"
NDK_BUILD="${ANDROID_NDK_HOME:+$ANDROID_NDK_HOME/ndk-build}"
if [[ -n "${ANDROID_NDK_HOME:-}" && ! -x "$NDK_BUILD" && -f "$ANDROID_NDK_HOME/ndk-build.cmd" ]]; then
  NDK_BUILD="$ANDROID_NDK_HOME/ndk-build.cmd"
fi
readonly NDK_BUILD

if [[ -z "$NDK_BUILD" || ! -f "$NDK_BUILD" ]]; then
  printf 'ANDROID_NDK_HOME must point to an Android NDK\n' >&2
  exit 1
fi

if [[ ! -d "$SOURCE/.git" ]]; then
  git clone --recurse-submodules --branch "$TAG" --single-branch "$REPOSITORY" "$SOURCE"
fi

actual_commit="$(git -C "$SOURCE" rev-parse HEAD)"
if [[ "$actual_commit" != "$COMMIT" ]]; then
  printf 'unexpected hev-socks5-tunnel commit: %s\n' "$actual_commit" >&2
  exit 1
fi

git -C "$SOURCE" submodule update --init --recursive
# Git for Windows may check out symlinks as small text files when symlink support is disabled.
# Materialize those links before invoking ndk-build so the native headers compile locally.
while IFS= read -r -d '' link_file; do
  link_target="$(cat "$link_file")"
  case "$link_target" in
    ../*|./*)
      real_target="$(cd "$(dirname "$link_file")" && cd "$(dirname "$link_target")" && pwd)/$(basename "$link_target")"
      if [[ -f "$real_target" ]]; then
        cp "$real_target" "$link_file"
      fi
      ;;
  esac
done < <(find "$SOURCE" -type f -size -200c -print0)
cp "$BRIDGE" "$GENERATED_BRIDGE"
trap 'rm -f "$GENERATED_BRIDGE"' EXIT
"$NDK_BUILD" \
  NDK_PROJECT_PATH="$ROOT/app/src/main/jni" \
  APP_BUILD_SCRIPT="$JNI" \
  NDK_APPLICATION_MK="$ROOT/app/src/main/jni/Application.mk" \
  HEV_SOURCE="$SOURCE" \
  APP_ABI="armeabi-v7a arm64-v8a x86_64" \
  NDK_LIBS_OUT="$OUTPUT" \
  REV_ID="$REV_ID"
