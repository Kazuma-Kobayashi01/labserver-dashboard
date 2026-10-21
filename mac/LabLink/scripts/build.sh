#!/bin/bash
# Builds LabLink.app (ad-hoc signed).
#   ARCH=arm64|x86_64   target architecture (default: this Mac's)
#   VERSION=1.0.0       bundle version
#   TOOLS_DIR=<dir>     directory holding `wg` and `wireguard-go` built for ARCH (default: /opt/homebrew/bin when ARCH
#                       is this Mac's architecture; for another architecture run scripts/build-tools.sh first)
#   OUT=build           output directory; the app lands in $OUT/LabLink.app
set -euo pipefail
cd "$(dirname "$0")/.."

HOST=$(uname -m)
ARCH=${ARCH:-$HOST}
VERSION=${VERSION:-1.0.0}
OUT=${OUT:-build}
case "$ARCH" in arm64|x86_64) ;; *) echo "ARCH must be arm64 or x86_64" >&2; exit 2 ;; esac
if [ -z "${TOOLS_DIR:-}" ]; then
  if [ "$ARCH" = "$HOST" ]; then TOOLS_DIR=/opt/homebrew/bin; [ "$ARCH" = x86_64 ] && TOOLS_DIR=/usr/local/bin
  else TOOLS_DIR="build/tools-$ARCH"; fi
fi
for b in wg wireguard-go; do
  [ -x "$TOOLS_DIR/$b" ] || { echo "missing $TOOLS_DIR/$b (run scripts/build-tools.sh, or: brew install wireguard-tools)" >&2; exit 1; }
  lipo -archs "$TOOLS_DIR/$b" | grep -qw "$ARCH" || { echo "$TOOLS_DIR/$b is not $ARCH" >&2; exit 1; }
done

APP="$OUT/LabLink.app"
swift build -c release --arch "$ARCH"
BIN="$(swift build -c release --arch "$ARCH" --show-bin-path)/LabLink"

rm -rf "$APP"; mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/LabLink"
cp Resources/lablink-helper Resources/install.sh "$APP/Contents/Resources/"
chmod 755 "$APP/Contents/Resources/lablink-helper" "$APP/Contents/Resources/install.sh"
for b in wg wireguard-go; do cp -L "$TOOLS_DIR/$b" "$APP/Contents/Resources/$b"; chmod 755 "$APP/Contents/Resources/$b"; done
ICONSET=$(mktemp -d)/AppIcon.iconset
swift scripts/make-icon.swift "$ICONSET"
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"
cat > "$APP/Contents/Info.plist" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>LabLink</string>
  <key>CFBundleDisplayName</key><string>LabLink</string>
  <key>CFBundleDevelopmentRegion</key><string>en</string>
  <key>CFBundleLocalizations</key><array><string>en</string><string>zh-Hans</string><string>ja</string></array>
  <key>CFBundleIdentifier</key><string>local.labserver.lablink</string>
  <key>CFBundleExecutable</key><string>LabLink</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$VERSION</string>
  <key>CFBundleVersion</key><string>$VERSION</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSPrincipalClass</key><string>NSApplication</string>
  <key>NSAppTransportSecurity</key><dict><key>NSAllowsArbitraryLoads</key><true/></dict>
  <key>NSLocalNetworkUsageDescription</key><string>LabLink 需要访问家里的服务器面板。 / LabLink needs to reach your home server's panels.</string>
</dict></plist>
PL
codesign --force --deep --sign - "$APP" >/dev/null
echo "built $APP ($ARCH, v$VERSION)"
