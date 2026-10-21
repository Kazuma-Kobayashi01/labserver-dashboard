#!/bin/bash
# Builds LabLink.app into ./build (ad-hoc signed). Needs Xcode toolchain and brew's wireguard-tools.
set -euo pipefail
cd "$(dirname "$0")/.."
APP=build/LabLink.app
swift build -c release
rm -rf "$APP"; mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp .build/release/LabLink "$APP/Contents/MacOS/LabLink"
cp Resources/lablink-helper Resources/install.sh "$APP/Contents/Resources/"; chmod 755 "$APP/Contents/Resources/lablink-helper" "$APP/Contents/Resources/install.sh"
for b in wg wireguard-go; do cp -L "/opt/homebrew/bin/$b" "$APP/Contents/Resources/$b"; chmod 755 "$APP/Contents/Resources/$b"; done
rm -rf build/AppIcon.iconset; swift scripts/make-icon.swift build/AppIcon.iconset
iconutil -c icns build/AppIcon.iconset -o "$APP/Contents/Resources/AppIcon.icns"
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
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSPrincipalClass</key><string>NSApplication</string>
  <key>NSAppTransportSecurity</key><dict><key>NSAllowsArbitraryLoads</key><true/></dict>
  <key>NSLocalNetworkUsageDescription</key><string>LabLink 需要访问家里的服务器面板。</string>
</dict></plist>
PL
codesign --force --deep --sign - "$APP" >/dev/null
echo "built $APP"
