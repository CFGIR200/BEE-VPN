#!/usr/bin/env bash
# =============================================================================
# BEE-VPN — publish the whole project to GitHub in ONE command
#
#   * creates github.com/CFGIR200/BEE-VPN        (if missing)
#   * pushes this source tree                    (branch: master)
#   * pushes tag v3.2.0                          -> triggers release.yml,
#     which builds fresh APKs and publishes them as Release assets
#   * creates the Release v3.2.0 and uploads the ALREADY-BUILT APKs from the
#     apk/ folder next to the project (so the release exists immediately,
#     even before the first CI build finishes)
#
# Usage:
#   GH_TOKEN=ghp_xxxxxxxxxxxxxxxx ./beevpn_github.sh [source-dir] [apk-dir]
#
#   source-dir defaults to the folder containing this script.
#   apk-dir     defaults to  <parent-of-source-dir>/apk  (where the delivered
#               BEE-VPN-3.2.0-*.apk files + SHA256SUMS.txt live).
#
# The token needs: classic PAT with "repo" scope (or fine-grained with
# Contents: read&write + Administration: read&write if the repo must be
# created). Revoke the token when you are done.
# Requires: git, curl, python3.
# =============================================================================
set -euo pipefail

OWNER="${OWNER:-CFGIR200}"
REPO="${REPO:-BEE-VPN}"
TAG="${TAG:-v3.2.0}"
VER="${TAG#v}"
BRANCH="${BRANCH:-master}"
API="https://api.github.com"
UP="https://uploads.github.com"
AUTH="Authorization: Bearer ${GH_TOKEN:-}"

log(){ printf '\n== %s ==\n' "$*"; }
die(){ printf 'ERROR: %s\n' "$*" >&2; exit 1; }

SRC_DIR="${1:-$(cd "$(dirname "$0")" && pwd)}"
APK_DIR="${2:-$(cd "$SRC_DIR/.." && pwd)/apk}"

[ -n "${GH_TOKEN:-}" ] || die "export GH_TOKEN first:  GH_TOKEN=ghp_xxx ./beevpn_github.sh"
[ -d "$SRC_DIR" ]      || die "source dir not found: $SRC_DIR"
command -v git     >/dev/null || die "git not installed"
command -v curl    >/dev/null || die "curl not installed"
command -v python3 >/dev/null || die "python3 not installed"

# ---------------------------------------------------------------------------
log "1/6  Checking token"
# ---------------------------------------------------------------------------
LOGIN=$(curl -sf -H "$AUTH" "$API/user" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("login",""))') \
  || die "token rejected by GitHub (check GH_TOKEN)"
echo "authenticated as: $LOGIN"
if [ "$LOGIN" != "$OWNER" ]; then
  echo "NOTE: token owner ($LOGIN) differs from OWNER ($OWNER) - assuming the repo already exists under $OWNER"
fi

# ---------------------------------------------------------------------------
log "2/6  Creating repository $OWNER/$REPO (skipped if it exists)"
# ---------------------------------------------------------------------------
CODE=$(curl -s -o /tmp/bee_repo.json -w '%{http_code}' -H "$AUTH" \
  -H "Accept: application/vnd.github+json" -X POST "$API/user/repos" \
  -d "{\"name\":\"$REPO\",\"description\":\"BEE-VPN - honey-themed VPN client (based on MSN-GUARD, AGPL-3.0)\",\"has_issues\":true,\"private\":false}")
case "$CODE" in
  201) echo "repository created" ;;
  422) echo "repository already exists - continuing" ;;
  *)   cat /tmp/bee_repo.json; die "repo creation failed (HTTP $CODE)" ;;
esac

# ---------------------------------------------------------------------------
log "3/6  Committing and pushing source (branch: $BRANCH)"
# ---------------------------------------------------------------------------
cd "$SRC_DIR"
if [ ! -d .git ]; then
  git init -b "$BRANCH" >/dev/null
  echo "git repository initialised"
fi
git config user.name  "${GIT_AUTHOR_NAME:-CFGIR200}"
git config user.email "${GIT_AUTHOR_EMAIL:-CFGIR200@users.noreply.github.com}"
git add -A
git commit -q --allow-empty -m "BEE-VPN $VER: full rebrand of MSN-GUARD 3.2.0 (bee theme, app.beevpn, fa+en)"
PUSH_URL="https://x-access-token:${GH_TOKEN}@github.com/${OWNER}/${REPO}.git"
git push -q "$PUSH_URL" "+HEAD:refs/heads/$BRANCH"
echo "source pushed: https://github.com/$OWNER/$REPO/tree/$BRANCH"

# ---------------------------------------------------------------------------
log "4/6  Pushing tag $TAG"
# ---------------------------------------------------------------------------
git tag -f "$TAG" -m "BEE-VPN $VER"
git push -q "$PUSH_URL" "+refs/tags/$TAG"
echo "tag pushed - GitHub Actions release.yml will now build fresh APKs"

# ---------------------------------------------------------------------------
log "5/6  Creating Release $TAG"
# ---------------------------------------------------------------------------
python3 - "$TAG" "$VER" > /tmp/bee_release.json <<'PYEOF'
import json, sys
tag, ver = sys.argv[1], sys.argv[2]
body = f"""BEE-VPN {ver} — نسخهٔ آمادهٔ نصب (versionCode 449)

Changes in this version:
- Same core as upstream MSN-GUARD 3.2.0 (versionCode 449)
- 7 connection modes: WireGuard, MASQUE, WARP-on-WARP (gool), Psiphon, Tor, Foxy, SHARD
- Bee theme and icons, Persian + English UI
- Telegram channel and admin contact in the About screen

**Downloads:** install the APK matching your device — most phones need `arm64-v8a`.
`SHA256SUMS.txt` contains checksums to verify your download.
"""
print(json.dumps({"tag_name": tag, "name": f"BEE-VPN {ver}", "body": body,
                  "draft": False, "prerelease": False}))
PYEOF
CODE=$(curl -s -o /tmp/bee_rel_resp.json -w '%{http_code}' -H "$AUTH" \
  -H "Accept: application/vnd.github+json" -X POST "$API/repos/$OWNER/$REPO/releases" \
  --data-binary @/tmp/bee_release.json)
if [ "$CODE" = "201" ]; then
  echo "release created"
elif [ "$CODE" = "422" ]; then
  echo "release already exists - continuing with asset upload"
else
  cat /tmp/bee_rel_resp.json; die "release creation failed (HTTP $CODE)"
fi
REL_ID=$(curl -sf -H "$AUTH" "$API/repos/$OWNER/$REPO/releases/tags/$TAG" \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')
echo "release id: $REL_ID"

# ---------------------------------------------------------------------------
log "6/6  Uploading APK files as release assets"
# ---------------------------------------------------------------------------
upload_asset(){  # $1 = file path
  local NAME RES AID
  NAME=$(basename "$1")
  RES=$(curl -s -X POST -H "$AUTH" -H "Content-Type: application/octet-stream" \
        --data-binary @"$1" \
        "$UP/repos/$OWNER/$REPO/releases/$REL_ID/assets?name=$NAME")
  if python3 -c 'import json,sys,os; d=json.load(sys.stdin); sys.exit(0 if "name" in d else 1)' <<<"$RES"; then
    echo "  uploaded: $NAME ($(python3 -c "import json,sys;d=json.load(sys.stdin);print(round(d['size']/1e6,1))" <<<"$RES") MB)"
    return 0
  fi
  if python3 -c 'import sys; sys.exit(0 if "already_exists" in sys.stdin.read() else 1)' <<<"$RES"; then
    echo "  asset $NAME already exists - replacing"
    AID=$(curl -sf -H "$AUTH" "$API/repos/$OWNER/$REPO/releases/$REL_ID/assets" \
          | python3 -c 'import json,sys;n=sys.argv[1];print(next(a["id"] for a in json.load(sys.stdin) if a["name"]==n))' "$NAME")
    curl -sf -X DELETE -H "$AUTH" "$API/repos/$OWNER/$REPO/releases/assets/$AID" >/dev/null
    RES=$(curl -s -X POST -H "$AUTH" -H "Content-Type: application/octet-stream" \
          --data-binary @"$1" \
          "$UP/repos/$OWNER/$REPO/releases/$REL_ID/assets?name=$NAME")
    echo "  replaced:  $NAME"
  else
    echo "  UPLOAD FAILED: $NAME"; echo "$RES" | head -3; return 1
  fi
}

if [ ! -d "$APK_DIR" ]; then
  echo "NOTE: apk dir not found ($APK_DIR) - skipping asset upload."
  echo "      CI (release.yml) will attach fresh APKs when the tag build finishes."
else
  SHASUMS=0
  for f in "$APK_DIR"/BEE-VPN-*.apk "$APK_DIR"/SHA256SUMS.txt; do
    [ -f "$f" ] || continue
    case "$(basename "$f")" in SHA256SUMS.txt) SHASUMS=1 ;; esac
    upload_asset "$f"
  done
  [ "$SHASUMS" = "1" ] || echo "NOTE: SHA256SUMS.txt not found in $APK_DIR"
fi

# ---------------------------------------------------------------------------
log "DONE"
# ---------------------------------------------------------------------------
cat <<EOF

  Repository : https://github.com/$OWNER/$REPO
  Release    : https://github.com/$OWNER/$REPO/releases/tag/$TAG
  Latest     : https://github.com/$OWNER/$REPO/releases/latest

The in-app updater reads releases/latest, so the update check now works too.
EOF
