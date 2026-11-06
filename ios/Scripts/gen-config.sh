#!/usr/bin/env bash
# =============================================================================
# BEE-VPN iOS — inject build configuration into Config.swift
#
# Mirrors the Android build exactly: BEE_LICENSE_URL / BEE_LICENSE_PUBKEY_B64 /
# BEE_TELEGRAM_URL / BEE_SUPPORT_URL come from the environment (GitHub Actions
# secrets on CI). With the license values unset the subscription gate is
# compiled out — the same rule as the Android build.yml warning.
# =============================================================================
set -euo pipefail
# Xcode runs this as a build phase with SRCROOT set and $0 pointing into
# DerivedData — prefer SRCROOT, fall back to the script location for manual
# runs from the ios/ directory.
if [ -n "${SRCROOT:-}" ]; then
  cd "$SRCROOT"
else
  cd "$(dirname "$0")/.."
fi

TARGET="BEE-VPN/License/Config.swift"

: "${BEE_LICENSE_URL:=}"
: "${BEE_LICENSE_PUBKEY_B64:=}"
: "${BEE_TELEGRAM_URL:=https://t.me/BEESELL_GP}"
: "${BEE_SUPPORT_URL:=https://t.me/Bee_hive_VPN}"

if [ -z "$BEE_LICENSE_URL" ]; then
  echo 'WARN: BEE_LICENSE_URL not set - subscription gate will be disabled in this build' >&2
fi
if [ -z "$BEE_LICENSE_PUBKEY_B64" ]; then
  echo 'WARN: BEE_LICENSE_PUBKEY_B64 not set - subscription gate will be disabled in this build' >&2
fi

sed -e "s|__BEE_LICENSE_URL__|$BEE_LICENSE_URL|g" \
    -e "s|__BEE_LICENSE_PUBKEY_B64__|$BEE_LICENSE_PUBKEY_B64|g" \
    -e "s|__BEE_TELEGRAM_URL__|$BEE_TELEGRAM_URL|g" \
    -e "s|__BEE_SUPPORT_URL__|$BEE_SUPPORT_URL|g" \
    "$TARGET.in" > "$TARGET"

echo "gen-config: wrote $TARGET (license url ${BEE_LICENSE_URL:+set}${BEE_LICENSE_URL:-empty})"
