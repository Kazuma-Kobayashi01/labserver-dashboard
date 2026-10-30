#!/bin/bash
# Builds "Lab bot.app" (ad-hoc signed) into $OUT (default: build).
#   ARCH=arm64|x86_64   target architecture (default: this Mac's)
#   VERSION=1.0.0       bundle version
set -euo pipefail
cd "$(dirname "$0")/.."

ARCH=${ARCH:-$(uname -m)}
VERSION=${VERSION:-1.0.0}
OUT=${OUT:-build}
APP="$OUT/Lab bot.app"

swift build -c release --arch "$ARCH"
BIN="$(swift build -c release --arch "$ARCH" --show-bin-path)/LabBot"

rm -rf "$APP"; mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/LabBot"
ICONSET=$(mktemp -d)/AppIcon.iconset
swift scripts/make-icon.swift "$ICONSET"
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"
cat > "$APP/Contents/Info.plist" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>Lab bot</string>
  <key>CFBundleDisplayName</key><string>Lab bot</string>
  <key>CFBundleDevelopmentRegion</key><string>zh-Hans</string>
  <key>CFBundleLocalizations</key><array><string>en</string><string>zh-Hans</string></array>
  <key>CFBundleIdentifier</key><string>com.labserver.labbot</string>
  <key>CFBundleExecutable</key><string>LabBot</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$VERSION</string>
  <key>CFBundleVersion</key><string>$VERSION</string>
  <key>LSMinimumSystemVersion</key><string>15.0</string>
  <key>LSApplicationCategoryType</key><string>public.app-category.productivity</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSPrincipalClass</key><string>NSApplication</string>
  <key>NSAppTransportSecurity</key><dict><key>NSAllowsArbitraryLoads</key><true/></dict>
  <key>NSLocalNetworkUsageDescription</key><string>Lab bot 需要访问家里服务器上的 bot。 / Lab bot talks to the bots on your home server.</string>
</dict></plist>
PL
codesign --force --deep --sign - "$APP" >/dev/null
echo "built $APP ($ARCH, v$VERSION)"
