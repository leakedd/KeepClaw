#!/usr/bin/env bash
# AndroClaw — build de l'APK (release signée) + installation sur l'appareil connecté.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"

cd "$ROOT"
[ -f local.properties ] || echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
[ -f keystore.properties ] || "$ROOT/scripts/init-keystore.sh"

# Les binaires natifs ne sont pas versionnés : ils se régénèrent depuis le sous-module picoclaw.
if [ ! -f app/src/main/jniLibs/arm64-v8a/libcore.so ] || [ ! -f app/src/main/jniLibs/arm64-v8a/libconsole.so ]; then
  echo "binaires natifs absents dans app/src/main/jniLibs/arm64-v8a/." >&2
  echo "clone initial : git clone --recursive <url>  (ou : git submodule update --init --recursive)" >&2
  echo "puis        : ./scripts/build-native.sh" >&2
  exit 1
fi

TASK="${1:-assembleRelease}"
echo "== gradle $TASK =="
./gradlew --no-daemon "$TASK" 2>&1 | tail -25

APK="$(ls -t app/build/outputs/apk/*/*.apk 2>/dev/null | head -1 || true)"
[ -n "$APK" ] && { echo; echo "APK : $ROOT/$APK"; ls -lh "$APK"; }

if [ "${2:-}" = "install" ] && [ -n "$APK" ]; then
  echo "== adb install -r =="
  adb install -r "$APK"
fi
