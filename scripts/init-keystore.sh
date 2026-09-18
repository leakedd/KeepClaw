#!/usr/bin/env bash
# Génère le keystore de release de KeepClaw (une seule fois) + keystore.properties (0600).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KS_DIR="$ROOT/keystore"
KS="$KS_DIR/keepclaw.jks"
PROPS="$ROOT/keystore.properties"

if [ -f "$KS" ]; then echo "keystore déjà présent : $KS"; exit 0; fi
mkdir -p "$KS_DIR"

PASS="$(openssl rand -hex 16)"
/opt/homebrew/opt/openjdk@21/bin/keytool -genkeypair -v \
  -keystore "$KS" -alias keepclaw -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=KeepClaw, OU=Personal, O=KeepClaw, L=Paris, C=FR" >/dev/null

cat > "$PROPS" <<EOF
storeFile=keystore/keepclaw.jks
storePassword=$PASS
keyAlias=keepclaw
keyPassword=$PASS
EOF
chmod 600 "$PROPS"
echo "keystore créé : $KS"
echo "propriétés     : $PROPS (0600)"
