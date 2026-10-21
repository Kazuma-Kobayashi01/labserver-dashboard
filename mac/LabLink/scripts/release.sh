#!/bin/bash
# Builds one DMG per architecture into dist/:
#   LabLink-<version>-arm64.dmg    (Apple silicon)  <- default
#   LabLink-<version>-x86_64.dmg   (Intel)          <- opt in: ARCHS="arm64 x86_64" ./scripts/release.sh
#   SHA256SUMS
# Each app bundles `wg` and `wireguard-go` for its own architecture.
#   ARCHS         architectures to build (default: arm64)
#   TOOLS_ARM64   dir with arm64 wg + wireguard-go   (default /opt/homebrew/bin)
#   TOOLS_X86_64  dir with x86_64 wg + wireguard-go  (default build/tools-x86_64; see scripts/build-tools.sh)
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION=${VERSION:-1.0.0}
ARCHS=${ARCHS:-arm64}
DIST=dist
TOOLS_ARM64=${TOOLS_ARM64:-/opt/homebrew/bin}
TOOLS_X86_64=${TOOLS_X86_64:-build/tools-x86_64}
rm -rf "$DIST" build/release; mkdir -p "$DIST"

for ARCH in $ARCHS; do
  TOOLS=$([ "$ARCH" = arm64 ] && echo "$TOOLS_ARM64" || echo "$TOOLS_X86_64")
  OUT="build/release/$ARCH"
  ARCH=$ARCH VERSION=$VERSION TOOLS_DIR=$TOOLS OUT=$OUT ./scripts/build.sh | tail -1

  APP="$OUT/LabLink.app"
  # every Mach-O in the bundle must be the target architecture only
  for f in "$APP/Contents/MacOS/LabLink" "$APP/Contents/Resources/wg" "$APP/Contents/Resources/wireguard-go"; do
    [ "$(lipo -archs "$f")" = "$ARCH" ] || { echo "$f is $(lipo -archs "$f"), expected $ARCH" >&2; exit 1; }
  done
  codesign --verify --deep --strict "$APP"

  STAGE="$OUT/dmg-root"; rm -rf "$STAGE"; mkdir -p "$STAGE"
  cp -R "$APP" "$STAGE/"; ln -s /Applications "$STAGE/Applications"
  DMG="$DIST/LabLink-$VERSION-$ARCH.dmg"
  hdiutil create -quiet -volname "LabLink $VERSION" -srcfolder "$STAGE" -fs HFS+ -format UDZO -imagekey zlib-level=9 -ov "$DMG"
  echo "created $DMG"
done
( cd "$DIST" && shasum -a 256 LabLink-*.dmg > SHA256SUMS && cat SHA256SUMS )
