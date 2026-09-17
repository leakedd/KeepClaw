#!/usr/bin/env bash
# AndroClaw — build de l'APK (release signée) + installation sur l'appareil connecté.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"

cd "$ROOT"
[ -f local.properties ] || echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
[ -f keystore.properties ] || "$ROOT/scripts/init-keystore.sh"

TASK="${1:-assembleRelease}"
echo "== gradle $TASK =="
./gradlew --no-daemon "$TASK" 2>&1 | tail -25

APK="$(ls -t app/build/outputs/apk/*/*.apk 2>/dev/null | head -1 || true)"
[ -n "$APK" ] && { echo; echo "APK : $ROOT/$APK"; ls -lh "$APK"; }

if [ "${2:-}" = "install" ] && [ -n "$APK" ]; then
  echo "== adb install -r =="
  adb install -r "$APK"
fi
