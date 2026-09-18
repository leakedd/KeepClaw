#!/usr/bin/env bash
# KeepClaw — compile les deux binaires Go (core + console) pour android/arm64
# et les dépose dans app/src/main/jniLibs/arm64-v8a/ sous la forme lib*.so
# (forme exigée par PackageManager pour être extraits vers nativeLibraryDir, zone exec-able).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/native/picoclaw"
JNI="$ROOT/app/src/main/jniLibs/arm64-v8a"
export PATH="/opt/homebrew/bin:$PATH"
export GOFLAGS="${GOFLAGS:-}"
export CGO_ENABLED=0
export GOOS=android
export GOARCH=arm64

mkdir -p "$JNI"

CONFIG_PKG="github.com/sipeed/picoclaw/pkg/config"
VERSION="$(cd "$SRC" && git describe --tags --always --dirty 2>/dev/null || echo dev)"
COMMIT="$(cd "$SRC" && git rev-parse --short HEAD 2>/dev/null || echo nogit)"
BUILDTIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
GOVER="$(go version | awk '{print $3}')"
LDFLAGS="-X ${CONFIG_PKG}.Version=${VERSION} -X ${CONFIG_PKG}.GitCommit=${COMMIT} -X ${CONFIG_PKG}.BuildTime=${BUILDTIME} -X ${CONFIG_PKG}.GoVersion=${GOVER} -s -w"

if [ ! -d "$SRC/cmd/picoclaw" ]; then
  echo "ERREUR : sources picoclaw absentes de $SRC" >&2
  echo "         récupérer le sous-module : git submodule update --init --recursive" >&2
  exit 1
fi

# Le lockfile upstream contient des clés dupliquées que pnpm refuse (ERR_PNPM_BROKEN_LOCKFILE).
# On le déduplique avant l'install : le bug est upstream, pas chez nous.
if [ -f "$ROOT/scripts/fix-lockfile.py" ] && [ -f "$SRC/web/frontend/pnpm-lock.yaml" ]; then
  python3 "$ROOT/scripts/fix-lockfile.py" "$SRC/web/frontend/pnpm-lock.yaml" >/dev/null 2>&1 \
    && echo "   lockfile vérifié/dédupliqué" || echo "   (déduplication lockfile ignorée)"
fi

# Branding : applique notre marque (KeepClaw) sur les sources du sous-module avant compilation.
# Idempotent (voir scripts/brand.py) : relancer ne change rien.
if [ -f "$ROOT/scripts/brand.py" ]; then
  echo "== 0/3 branding =="
  python3 "$ROOT/scripts/brand.py" | sed 's/^/   /'
fi

# Correctifs UI mobiles : patches versionnes (scripts/patches/*.patch), idempotents.
# A lancer avant le frontend : un patch fraichement applique invalide dist/.
if [ -f "$ROOT/scripts/patch-ui.sh" ]; then
  echo "== 0bis/3 correctifs UI =="
  "$ROOT/scripts/patch-ui.sh" | sed 's/^/   /'
fi

# Branding console : logo avec texte + favicons generes depuis le logo KeepClaw
# (les binaires du sous-module ne peuvent pas etre patchees proprement).
if [ -f "$ROOT/scripts/make-icons.py" ]; then
  echo "== 0ter/3 branding console =="
  python3 "$ROOT/scripts/make-icons.py" --web | sed 's/^/   /'
fi

echo "== 1/3 frontend (embed dans web/backend/dist) =="
if [ ! -f "$SRC/web/backend/dist/index.html" ]; then
  echo "   build frontend requis (pnpm + vite)…"
  ( cd "$SRC/web/frontend" \
    && CI=true pnpm install --frozen-lockfile )
  # La table de routes du routeur TanStack (src/routeTree.gen.ts) n'est regeneree
  # QUE par vite : le script upstream « build:backend » fait « tsc -b && vite build »,
  # donc tsc type-check une table perimee (et echoue sur les routes ajoutees par nos
  # patches) des qu'on part d'un arbre propre — clone neuf ou apres « git clean ».
  # On lance donc vite une premiere fois (807 ms) pour regenerer la table, puis le
  # script upstream qui re-type-check et reconstruit.
  echo "   regeneration de la table de routes (vite)…"
  ( cd "$SRC/web/frontend" \
    && CI=true pnpm exec vite build --outDir ../backend/dist --emptyOutDir >/dev/null 2>&1 )
  ( cd "$SRC/web/frontend" \
    && CI=true pnpm build:backend )
else
  echo "   dist déjà présent"
fi

echo "== 2/3 moteur (core) =="
( cd "$SRC" && GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
    go build -tags goolm,stdjson -ldflags "$LDFLAGS" \
    -o "$JNI/libcore.so" ./cmd/picoclaw )

echo "== 3/3 console =="
( cd "$SRC/web" && GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
    go build -tags stdjson -ldflags "$LDFLAGS" \
    -o "$JNI/libconsole.so" ./backend )

echo
ls -lh "$JNI" | tail -4
file "$JNI"/*.so 2>/dev/null | sed 's/^/  /' || true
echo "OK — binaires natifs prêts (version ${VERSION} / ${COMMIT})"
