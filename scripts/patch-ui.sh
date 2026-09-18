#!/usr/bin/env bash
# KeepClaw — applique les correctifs UI mobiles au sous-module picoclaw.
#
# Le sous-module reste epingle sur son commit upstream : nos correctifs sont des
# patches versionnes (scripts/patches/*.patch), appliques par ce script, comme le
# branding de scripts/brand.py. Idempotent : relancer ne change rien.
#
# Si un patch vient d'etre applique, on invalide web/backend/dist pour que le
# frontend soit recompile (sinon build-native.sh reutiliserait un bundle perime).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/native/picoclaw"
PATCH_DIR="$ROOT/scripts/patches"

if [ ! -d "$SRC" ]; then
  echo "ERREUR : sous-module absent ($SRC)" >&2
  echo "         recuperer : git submodule update --init --recursive" >&2
  exit 1
fi

shopt -s nullglob
patches=("$PATCH_DIR"/*.patch)
if [ ${#patches[@]} -eq 0 ]; then
  echo "patch-ui : aucun patch"
  exit 0
fi

applied=0
for p in "${patches[@]}"; do
  name="$(basename "$p")"
  if git -C "$SRC" apply --reverse --check "$p" >/dev/null 2>&1; then
    echo "   $name : deja applique"
  elif git -C "$SRC" apply --check "$p" >/dev/null 2>&1; then
    git -C "$SRC" apply "$p"
    echo "   $name : applique"
    applied=1
  else
    echo "ERREUR : $name ne s'applique pas (sous-module modifie ou upstream different)" >&2
    exit 1
  fi
done

if [ "$applied" = "1" ] && [ -d "$SRC/web/backend/dist" ]; then
  rm -rf "$SRC/web/backend/dist"
  echo "   dist frontend invalide (rebuild requis)"
fi
