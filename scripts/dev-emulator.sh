#!/usr/bin/env bash
# KeepClaw — boucle de dev émulateur : boot → build → install → lancement → logs.
# Usage : scripts/dev-emulator.sh [boot|build|install|launch|logs|all]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export PATH="$HOME/Library/Android/sdk/platform-tools:$HOME/Library/Android/sdk/emulator:$PATH"
SERIAL="${SERIAL:-emulator-5554}"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
PKG="dev.keepclaw.debug"
ACTIVITY="$PKG/dev.keepclaw.MainActivity"
CMD="${1:-all}"

wait_boot() {
  adb -s "$SERIAL" wait-for-device
  until [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 2
  done
  echo "émulateur prêt ($SERIAL)"
}

case "$CMD" in
  boot)
    if ! adb devices | grep -q "^$SERIAL"; then
      nohup emulator -avd keepclaw -no-audio -no-boot-anim -gpu auto -no-snapshot >/tmp/ac-emu.log 2>&1 &
    fi
    wait_boot
    ;;
  build)
    "$ROOT/scripts/build-native.sh"
    "$ROOT/scripts/build-apk.sh" assembleDebug
    ;;
  install)
    adb -s "$SERIAL" install -r "$APK"
    ;;
  launch)
    adb -s "$SERIAL" shell am start -n "$ACTIVITY" >/dev/null
    adb -s "$SERIAL" forward tcp:18800 tcp:18800 >/dev/null
    echo "console : http://127.0.0.1:18800 (login = mot de passe console)"
    ;;
  logs)
    adb -s "$SERIAL" logcat -v time | grep -iE "KeepClaw|libcore|libconsole|go:"
    ;;
  all)
    "$0" boot
    "$0" build
    "$0" install
    "$0" launch
    ;;
  *)
    echo "usage: $0 [boot|build|install|launch|logs|all]" >&2
    exit 1
    ;;
esac
