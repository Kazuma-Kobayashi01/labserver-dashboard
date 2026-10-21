#!/bin/bash
# Builds `wg` and `wireguard-go` for ARCH from the exact sources Homebrew ships, into build/tools-$ARCH/.
# Needed for the architecture you are not running on (Homebrew has no Intel bottles any more).
#   ARCH=x86_64 ./scripts/build-tools.sh        # needs Xcode CLT; wireguard-go needs Go 1.23+ (or set GO_BUILD_HOST)
set -euo pipefail
cd "$(dirname "$0")/.."
ARCH=${ARCH:?set ARCH=arm64 or ARCH=x86_64}
TOOLS_VER=1.0.20260223;  TOOLS_REV=49ce333da02056ae7b22ee2aeb6afe8aaed79b19
GO_VER=0.0.20250522;     GO_REV=f333402bd9cbe0f3eeb02507bd14e23d7d639280
OUT="$PWD/build/tools-$ARCH"; SRC="$PWD/build/tools-src"
mkdir -p "$OUT" "$SRC"

fetch() { # name tag rev
  [ -d "$SRC/$1" ] || git clone -q --depth 1 --branch "$2" "https://git.zx2c4.com/$1.git" "$SRC/$1"
  [ "$(git -C "$SRC/$1" rev-parse HEAD)" = "$3" ] || { echo "$1: unexpected revision" >&2; exit 1; }
}
fetch wireguard-tools "v$TOOLS_VER" "$TOOLS_REV"
fetch wireguard-go "$GO_VER" "$GO_REV"

make -C "$SRC/wireguard-tools/src" clean >/dev/null 2>&1 || true
make -C "$SRC/wireguard-tools/src" wg CC="clang -arch $ARCH -mmacosx-version-min=14.0" LDFLAGS="-arch $ARCH -mmacosx-version-min=14.0"
cp "$SRC/wireguard-tools/src/wg" "$OUT/wg"

GOARCH=$([ "$ARCH" = x86_64 ] && echo amd64 || echo arm64)
( cd "$SRC/wireguard-go" && CGO_ENABLED=0 GOOS=darwin GOARCH=$GOARCH go build -trimpath -ldflags "-s -w" -o "$OUT/wireguard-go" . )
lipo -info "$OUT/wg" "$OUT/wireguard-go"
