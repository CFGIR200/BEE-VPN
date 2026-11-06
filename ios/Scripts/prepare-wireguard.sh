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

# Modern Xcode (26+) marks WireGuardKit's platform constants (.iOS(.v15),
# .macOS(.v12), and even .v13/.v16) as unavailable — the SDK floor keeps
# moving. The bulletproof patch is to drop the platforms block entirely:
# SwiftPM then uses the toolchain's own defaults, which are always valid.
python3 - "$WG/Package.swift" <<'EOF'
import re, sys
path = sys.argv[1]
src = open(path).read()
patched = re.sub(r'platforms:\s*\[[^\]]*\]\s*,\s*\n', '', src, count=1)
open(path, 'w').write(patched)
print('platforms block removed from', path, '-', 'patched' if patched != src else 'already clean')
EOF

# New SDKs (Xcode 16+) split Darwin types into fine-grained modules, so
# WireGuardKitC.h needs an explicit <sys/types.h> before using u_int32_t and
# friends (wireguard-apple issue / stackoverflow.com/questions/79002585).
HEADER="$WG/Sources/WireGuardKitC/WireGuardKitC.h"
if ! grep -q '#include <sys/types.h>' "$HEADER"; then
  sed -i.bak '1i\
#include <sys/types.h>
' "$HEADER"
  echo "patched $HEADER (sys/types.h)"
fi

: "${PLATFORM_NAME:=iphoneos}"
: "${SDKROOT:=$(xcrun --sdk iphoneos --show-sdk-path)}"
: "${ARCHS:=arm64}"
export PLATFORM_NAME SDKROOT ARCHS

make -C "$WG/Sources/WireGuardKitGo" -j"$(sysctl -n hw.ncpu 2>/dev/null || echo 8)"
ls -la "$WG/Sources/WireGuardKitGo/out"
