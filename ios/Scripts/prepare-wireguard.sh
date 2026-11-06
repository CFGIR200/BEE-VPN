#!/usr/bin/env bash
# =============================================================================
# BEE-VPN iOS — prepare WireGuardKit + build the Go bridge (libwg-go.a)
#
# Clones wireguard-apple 1.0.16-27 to ../wireguard-apple (if missing), lifts
# its SwiftPM platform floor (Xcode 26 marks .iOS(.v15)/.macOS(.v12) as
# unavailable), then compiles the Go bridge for arm64. Used by CI and by
# local Mac builds (see README-FA.md).
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."   # project root (ios/)

WG=../wireguard-apple
if [ ! -d "$WG/Sources/WireGuardKitGo" ]; then
  git clone --depth 1 --branch 1.0.16-27 \
    https://github.com/WireGuard/wireguard-apple.git "$WG"
fi

# Xcode 26 raised the SwiftPM platform floor — lift the package to match.
sed -i.bak \
  -e 's/\.macOS(\.v12)/.macOS(.v13)/' \
  -e 's/\.iOS(\.v15)/.iOS(.v16)/' \
  "$WG/Package.swift"

: "${PLATFORM_NAME:=iphoneos}"
: "${SDKROOT:=$(xcrun --sdk iphoneos --show-sdk-path)}"
: "${ARCHS:=arm64}"
export PLATFORM_NAME SDKROOT ARCHS

make -C "$WG/Sources/WireGuardKitGo" -j"$(sysctl -n hw.ncpu 2>/dev/null || echo 8)"
ls -la "$WG/Sources/WireGuardKitGo/out"
