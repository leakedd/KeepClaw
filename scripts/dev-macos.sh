#!/usr/bin/env bash
# KeepClaw — boucle de dev sur macOS.
#
# Compile le moteur + la console pour darwin (même code, même branding que l'APK)
# et sert la console en local, pour itérer sur l'UI sans passer par le téléphone.
#
#   ./scripts/dev-macos.sh              # console sur http://127.0.0.1:18899
#   AC_PORT=19000 ./scripts/dev-macos.sh
#
# Données de dev isolées dans dist/dev/home (jamais le dossier de l'app Android).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/native/picoclaw"
OUT="$ROOT/dist/dev"
PORT="${AC_PORT:-18899}"
export PATH="/opt/homebrew/bin:$PATH"

if [ ! -d "$SRC/cmd/picoclaw" ]; then
  echo "sources absentes : git submodule update --init --recursive" >&2
  exit 1
fi

echo "== branding =="
python3 "$ROOT/scripts/brand.py" | sed 's/^/   /'

if [ ! -f "$SRC/web/backend/dist/index.html" ]; then
  echo "== frontend (pnpm + vite) =="
  [ -f "$SRC/web/frontend/pnpm-lock.yaml" ] && python3 "$ROOT/scripts/fix-lockfile.py" "$SRC/web/frontend/pnpm-lock.yaml" >/dev/null 2>&1 || true
  ( cd "$SRC/web/frontend" && CI=true pnpm install --frozen-lockfile && pnpm build:backend )
fi

mkdir -p "$OUT/home"
echo "== binaires darwin =="
( cd "$SRC" && GOOS=darwin GOARCH=arm64 CGO_ENABLED=0 go build -tags goolm,stdjson -ldflags "-s -w" -o "$OUT/ac-core" ./cmd/picoclaw )
( cd "$SRC/web" && GOOS=darwin GOARCH=arm64 CGO_ENABLED=0 go build -tags stdjson -ldflags "-s -w" -o "$OUT/ac-console" ./backend )

echo "== console : http://127.0.0.1:$PORT =="
cd "$OUT"
KEEPCLAW_HOME="$OUT/home" \
KEEPCLAW_BINARY="$OUT/ac-core" \
KEEPCLAW_LAUNCHER_HOST="127.0.0.1" \
exec "$OUT/ac-console" -host 127.0.0.1 -port "$PORT"
