#!/usr/bin/env bash
# KeepClaw — export des artefacts : APK release signé + archive source + sommes de contrôle.
# Usage : ./scripts/export.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
cd "$ROOT"

VER="$(grep -m1 'versionName' app/build.gradle.kts | sed 's/.*"\(.*\)".*/\1/')"
DIST="$ROOT/dist"
mkdir -p "$DIST"

APK="$(ls -t app/build/outputs/apk/release/*.apk 2>/dev/null | head -1 || true)"
if [ -z "$APK" ]; then
  echo "aucun APK release : lance d'abord ./scripts/build-apk.sh assembleRelease" >&2
  exit 1
fi
OUT="$DIST/KeepClaw-$VER-release.apk"
cp "$APK" "$OUT"

APKSIGNER="$(ls "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
if [ -n "$APKSIGNER" ]; then
  echo "== signature =="
  "$APKSIGNER" verify --print-certs "$OUT" | sed -n '1p;/Signer #1 certificate DN/p;/Signer #1 certificate SHA-256/p'
else
  echo "apksigner introuvable (build-tools) — signature non vérifiée"
fi

echo "== archive source =="
git archive --format=tar.gz --prefix="KeepClaw-$VER/" -o "$DIST/KeepClaw-$VER-source.tar.gz" HEAD

cd "$DIST"
shasum -a 256 ./*.apk ./*.tar.gz > SHA256SUMS.txt
echo "== dist/ =="
ls -lh "$DIST"
cat SHA256SUMS.txt
