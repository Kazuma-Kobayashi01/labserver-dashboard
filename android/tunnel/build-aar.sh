#!/bin/bash
# Builds ../app/libs/labtunnel.aar (the in-app WireGuard tunnel) with gomobile.
# Needs: Go, the Android SDK + NDK (ANDROID_HOME, or ~/Library/Android/sdk), and gomobile:
#   brew install go && go install golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest
#   ANDROID_ABIS=android/arm64,android/amd64   target ABIs (default: arm64 phones + x86_64 emulator)
set -euo pipefail
cd "$(dirname "$0")"

export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
export PATH="$PATH:$(go env GOPATH)/bin"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME=$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | head -1)   # oldest installed NDK: gomobile lags behind new ones
  export ANDROID_NDK_HOME
fi
echo "NDK: $ANDROID_NDK_HOME"

go get golang.org/x/mobile/bind >/dev/null 2>&1 || true
gomobile init
mkdir -p ../app/libs
gomobile bind -target "${ANDROID_ABIS:-android/arm64,android/amd64}" -androidapi 26 -ldflags "-s -w" \
  -o ../app/libs/labtunnel.aar ./labtunnel
ls -la ../app/libs/labtunnel.aar
