#!/bin/bash
# Downloads the official wstunnel build for Android (arm64) into the app's jniLibs, after checking its sha256.
# The binary is not committed (about 10 MB); run this once before building. See app/.../tunnel/TcpCarrier.kt.
set -euo pipefail
cd "$(dirname "$0")/.."
V=11.1.0
SHA=6f00cc63740f1602168e5d6a5b0bc5dab6bbe77ef613e2b11fe68f881641ce7f
DEST=app/src/main/jniLibs/arm64-v8a/libwstunnel.so
[ -x "$DEST" ] && { echo "already there: $DEST"; exit 0; }
T=$(mktemp -d)
curl -fsSL -o "$T/w.tgz" "https://github.com/erebe/wstunnel/releases/download/v$V/wstunnel_${V}_android_arm64.tar.gz"
[ "$(shasum -a 256 "$T/w.tgz" | cut -d' ' -f1)" = "$SHA" ] || { echo "sha256 mismatch" >&2; exit 1; }
tar xzf "$T/w.tgz" -C "$T" wstunnel
mkdir -p "$(dirname "$DEST")"
install -m 755 "$T/wstunnel" "$DEST"
echo "installed $DEST"
