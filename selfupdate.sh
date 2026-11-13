#!/data/data/com.termux/files/usr/bin/bash
#
# Tanoshi for Termux — self update from the project's GitHub repository.
#
# Looks up the newest release in spainclaro-debug/tanoshi-termux, downloads the
# prebuilt package, verifies its checksum and applies it in place: program files
# and plugins are replaced, your data/ is never touched.
#
# This is the "use the ready-made build" updater. To rebuild from upstream
# Tanoshi source instead, use update.sh.
#
# Usage:
#   bash selfupdate.sh [options]
#
# Options:
#   --check      only report whether a newer package exists
#   --force      reinstall the newest package even if already current
#   --rollback   restore the backups taken by the previous self update
#   --yes        assume yes for prompts
#   -h, --help   show this help

set -euo pipefail

# Resolve symlinks so the script works even when invoked through a link.
_SELF="${BASH_SOURCE[0]:-$0}"
while [ -L "$_SELF" ]; do
  _link="$(readlink "$_SELF")"
  case "$_link" in
    /*) _SELF="$_link" ;;
    *)  _SELF="$(dirname "$_SELF")/$_link" ;;
  esac
done
PKG_DIR="$(cd "$(dirname "$_SELF")" && pwd -P)"
# shellcheck source=lib/common.sh
. "$PKG_DIR/lib/common.sh"

# Where the prebuilt packages live.
PACKAGE_OWNER="spainclaro-debug"
PACKAGE_REPO="tanoshi-termux"
PACKAGE_ASSET="tanoshi-termux-aarch64.tar.gz"
PACKAGE_SUMS="${PACKAGE_ASSET}.sha256"
RAW_BASE="https://raw.githubusercontent.com/${PACKAGE_OWNER}/${PACKAGE_REPO}/main"

CHECK_ONLY=0
FORCE=0
ROLLBACK=0

while [ $# -gt 0 ]; do
  case "$1" in
    --check) CHECK_ONLY=1; shift ;;
    --force) FORCE=1; shift ;;
    --rollback) ROLLBACK=1; shift ;;
    --yes|-y) ASSUME_YES=1; shift ;;
    -h|--help) sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
done

require_termux

BACKUP_ROOT="$PKG_DIR/backups"
CURRENT_TAG="$(cat "$PKG_DIR/PACKAGE_TAG" 2>/dev/null || echo "unknown")"

# ---------------------------------------------------------------------------
# Rollback
# ---------------------------------------------------------------------------
if [ "$ROLLBACK" = "1" ]; then
  step "Rolling back the previous self update"
  LATEST_BACKUP="$(ls -1d "$BACKUP_ROOT"/selfupdate-*/ 2>/dev/null | sort | tail -1)"
  [ -n "$LATEST_BACKUP" ] || die "no self-update backup found in $BACKUP_ROOT"
  say "restoring: $LATEST_BACKUP"
  if ! confirm "Replace the current program files with this backup?" yes; then
    die "aborted"
  fi
  svc_stop || true
  [ -d "$LATEST_BACKUP/bin" ] && cp -a "$LATEST_BACKUP/bin/." "$PKG_DIR/bin/"
  if [ -d "$LATEST_BACKUP/plugins" ]; then
    # Remove plugins the backup does not contain, then restore.
    for existing in "$PKG_DIR"/plugins/*.so; do
      [ -e "$existing" ] || continue
      base="$(basename "$existing")"
      [ -f "$LATEST_BACKUP/plugins/$base" ] || rm -f "$existing"
    done
    cp -a "$LATEST_BACKUP/plugins/." "$PKG_DIR/plugins/"
  fi
  [ -f "$LATEST_BACKUP/VERSION" ] && cp -f "$LATEST_BACKUP/VERSION" "$PKG_DIR/VERSION"
  [ -f "$LATEST_BACKUP/LIB_VERSION" ] && cp -f "$LATEST_BACKUP/LIB_VERSION" "$PKG_DIR/LIB_VERSION"
  [ -f "$LATEST_BACKUP/PACKAGE_TAG" ] && cp -f "$LATEST_BACKUP/PACKAGE_TAG" "$PKG_DIR/PACKAGE_TAG"
  ok "restored package ${CURRENT_TAG:-?}"
  svc_start || true
  exit 0
fi

# True when the first tag is newer than the second.
#
# Tags look like `v0.40.0-termux.2`: an upstream version plus a package
# revision. Compare both, so a release is never "updated" backwards.
tag_is_newer() {
  python3 - "$1" "$2" <<'PY'
import re
import sys

def key(tag: str):
    match = re.match(r"^v?(\d+(?:\.\d+)*)(?:-termux\.?(\d+))?", tag.strip())
    if not match:
        return (0, 0, 0, 0)
    version = tuple(int(part) for part in match.group(1).split("."))
    revision = int(match.group(2) or 0)
    return version + (revision,)

left, right = key(sys.argv[1]), key(sys.argv[2])
width = max(len(left), len(right))
left += (0,) * (width - len(left))
right += (0,) * (width - len(right))
sys.exit(0 if left > right else 1)
PY
}

# ---------------------------------------------------------------------------
# 1. Ask GitHub for the newest package
# ---------------------------------------------------------------------------
step "Checking ${PACKAGE_OWNER}/${PACKAGE_REPO} for the latest package"
say "installed: $CURRENT_TAG"

TMP_DIR=""
cleanup() { [ -n "$TMP_DIR" ] && rm -rf "$TMP_DIR"; }
trap cleanup EXIT

TMP_DIR="$(mktemp -d "${TMPDIR:-${PREFIX}/tmp}/tanoshi-selfupdate.XXXXXX")" \
  || die "could not create a temporary directory"

META="$(python3 - "$PACKAGE_OWNER" "$PACKAGE_REPO" "$PACKAGE_ASSET" <<'PY'
import json
import sys
import urllib.error
import urllib.request

owner, repo, asset_name = sys.argv[1], sys.argv[2], sys.argv[3]
url = f"https://api.github.com/repos/{owner}/{repo}/releases/latest"
req = urllib.request.Request(url, headers={
    "Accept": "application/vnd.github+json",
    "User-Agent": "tanoshi-selfupdate",
})
try:
    data = json.load(urllib.request.urlopen(req, timeout=60))
except urllib.error.HTTPError as exc:
    print(f"ERR\tHTTP {exc.code}", file=sys.stderr)
    sys.exit(1)
except Exception as exc:  # noqa: BLE001
    print(f"ERR\t{exc}", file=sys.stderr)
    sys.exit(1)

tag = data.get("tag_name", "")
tar_url = ""
sum_url = ""
for asset in data.get("assets", []):
    if asset.get("name") == asset_name:
        tar_url = asset.get("browser_download_url", "")
    elif asset.get("name") == asset_name + ".sha256":
        sum_url = asset.get("browser_download_url", "")

print(f"TAG\t{tag}")
print(f"TAR\t{tar_url}")
print(f"SUM\t{sum_url}")
PY
)" || die "could not query GitHub releases (network or rate limit); try again later"

LATEST_TAG="$(printf '%s\n' "$META" | sed -n 's/^TAG\t//p')"
TAR_URL="$(printf '%s\n' "$META" | sed -n 's/^TAR\t//p')"
SUM_URL="$(printf '%s\n' "$META" | sed -n 's/^SUM\t//p')"

# Fall back to the copies committed on main when a release has no assets.
if [ -z "$TAR_URL" ]; then
  warn "no release asset found; falling back to the copies in the repository"
  TAR_URL="$RAW_BASE/$PACKAGE_ASSET"
  SUM_URL="$RAW_BASE/$PACKAGE_SUMS"
  [ -n "$LATEST_TAG" ] || LATEST_TAG="main"
fi

[ -n "$TAR_URL" ] || die "could not find $PACKAGE_ASSET in the latest release"
say "available: ${LATEST_TAG:-unknown}"

UP_TO_DATE=1
if [ "$FORCE" = "1" ] || tag_is_newer "$LATEST_TAG" "$CURRENT_TAG"; then
  UP_TO_DATE=0
fi

if [ "$UP_TO_DATE" = "1" ]; then
  ok "already on the latest package ($CURRENT_TAG)"
  exit 0
fi

if [ "$CHECK_ONLY" = "1" ]; then
  say ""
  say "A newer package is available: $CURRENT_TAG -> $LATEST_TAG"
  say "Run 'bash selfupdate.sh' to install it."
  exit 0
fi

say ""
say "This will replace the program files and plugins."
say "Your data directory ($(data_dir)) is not touched."
if ! confirm "Continue?" yes; then
  die "aborted"
fi

# ---------------------------------------------------------------------------
# 2. Download and verify
# ---------------------------------------------------------------------------
step "Downloading package"
curl -fL --retry 3 --connect-timeout 30 -o "$TMP_DIR/$PACKAGE_ASSET" "$TAR_URL" \
  || die "download failed: $TAR_URL"
ok "downloaded $(du -h "$TMP_DIR/$PACKAGE_ASSET" | cut -f1)"

step "Verifying checksum"
if curl -fsL --retry 2 -o "$TMP_DIR/$PACKAGE_SUMS" "$SUM_URL" 2>/dev/null \
   && [ -s "$TMP_DIR/$PACKAGE_SUMS" ]; then
  expected="$(awk '{print $1}' "$TMP_DIR/$PACKAGE_SUMS" | head -1)"
  actual="$(sha256sum "$TMP_DIR/$PACKAGE_ASSET" | awk '{print $1}')"
  if [ -n "$expected" ] && [ "$expected" != "$actual" ]; then
    die "checksum mismatch — refusing to install (expected $expected, got $actual)"
  fi
  ok "sha256 matches ($actual)"
else
  warn "checksum file unavailable; continuing without verification"
fi

# ---------------------------------------------------------------------------
# 3. Unpack into a scratch directory
# ---------------------------------------------------------------------------
step "Extracting"
EXTRACT_DIR="$TMP_DIR/pkg"
mkdir -p "$EXTRACT_DIR"
tar -xzf "$TMP_DIR/$PACKAGE_ASSET" -C "$EXTRACT_DIR" || die "could not extract the package"
NEW_ROOT="$EXTRACT_DIR/tanoshi"
[ -d "$NEW_ROOT" ] || NEW_ROOT="$(find "$EXTRACT_DIR" -maxdepth 1 -mindepth 1 -type d | head -1)"
[ -x "$NEW_ROOT/bin/tanoshi" ] || die "the package does not contain bin/tanoshi"
[ -d "$NEW_ROOT/plugins" ] || die "the package does not contain plugins/"
ok "package ${LATEST_TAG} extracted"

# ---------------------------------------------------------------------------
# 4. Back up, then swap program files
# ---------------------------------------------------------------------------
step "Backing up current program files"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="$BACKUP_ROOT/selfupdate-$STAMP"
mkdir -p "$BACKUP_DIR/bin" "$BACKUP_DIR/plugins"
[ -x "$PKG_DIR/bin/tanoshi" ] && cp -a "$PKG_DIR/bin/tanoshi" "$BACKUP_DIR/bin/"
cp -a "$PKG_DIR"/plugins/*.so "$BACKUP_DIR/plugins/" 2>/dev/null || true
for f in VERSION LIB_VERSION PACKAGE_TAG; do
  [ -f "$PKG_DIR/$f" ] && cp -f "$PKG_DIR/$f" "$BACKUP_DIR/$f"
done
ok "backup: $BACKUP_DIR"
ls -1d "$BACKUP_ROOT"/selfupdate-*/ 2>/dev/null | sort | head -n -5 | while read -r old; do
  [ -n "$old" ] && rm -rf "$old"
done

WAS_RUNNING=0
if svc_running; then
  WAS_RUNNING=1
  step "Stopping server"
  svc_stop
fi

step "Installing new program files"
# Replace the binary atomically so a running worker keeps its inode.
mkdir -p "$PKG_DIR/bin"
cp "$NEW_ROOT/bin/tanoshi" "$PKG_DIR/bin/.tanoshi.new"
chmod 700 "$PKG_DIR/bin/.tanoshi.new"
mv -f "$PKG_DIR/bin/.tanoshi.new" "$PKG_DIR/bin/tanoshi"

# Sync plugins exactly: drop ones the new package no longer ships.
mkdir -p "$PKG_DIR/plugins"
for existing in "$PKG_DIR"/plugins/*.so; do
  [ -e "$existing" ] || continue
  base="$(basename "$existing")"
  if [ ! -f "$NEW_ROOT/plugins/$base" ]; then
    rm -f "$existing"
  fi
done
cp -a "$NEW_ROOT"/plugins/*.so "$PKG_DIR/plugins/" 2>/dev/null || true
chmod 700 "$PKG_DIR"/plugins/*.so 2>/dev/null || true
# Clear runtime scratch copies left by a previous server run.
find "$PKG_DIR/plugins" -maxdepth 1 -name '.tanoshi-staged-*.so' -delete 2>/dev/null || true

# Refresh the helper scripts, lib and patch set so this updater stays current.
for entry in lib extensions patches vendor; do
  if [ -d "$NEW_ROOT/$entry" ]; then
    rm -rf "${PKG_DIR:?}/$entry"
    cp -a "$NEW_ROOT/$entry" "$PKG_DIR/$entry"
  fi
done
for entry in install.sh update.sh reinstall.sh uninstall.sh selfupdate.sh README.md; do
  [ -f "$NEW_ROOT/$entry" ] && cp -f "$NEW_ROOT/$entry" "$PKG_DIR/$entry"
done
chmod +x "$PKG_DIR"/*.sh 2>/dev/null || true

# Version markers.
[ -f "$NEW_ROOT/VERSION" ] && cp -f "$NEW_ROOT/VERSION" "$PKG_DIR/VERSION"
[ -f "$NEW_ROOT/LIB_VERSION" ] && cp -f "$NEW_ROOT/LIB_VERSION" "$PKG_DIR/LIB_VERSION"
[ -f "$NEW_ROOT/PACKAGE_TAG" ] && cp -f "$NEW_ROOT/PACKAGE_TAG" "$PKG_DIR/PACKAGE_TAG"
ok "program files replaced"

# ---------------------------------------------------------------------------
# 5. Repair config, restart, verify
# ---------------------------------------------------------------------------
step "Repairing runtime layout"
# Re-source in case lib/common.sh changed.
# shellcheck source=lib/common.sh
. "$PKG_DIR/lib/common.sh"
ensure_data_dirs
ensure_secret_length
install_control_command

if [ "$WAS_RUNNING" = "1" ]; then
  step "Starting server"
  if svc_start; then
    report_plugin_health || warn "some plugins did not load; try: bash selfupdate.sh --rollback"
    say ""
    say "Updated: $CURRENT_TAG -> ${LATEST_TAG}"
    say "Web UI:  http://127.0.0.1:$(svc_port)"
  else
    warn "server did not start — rolling back is available: bash selfupdate.sh --rollback"
    exit 1
  fi
else
  say ""
  say "Server was not running. Start it with: tanoshi-ctl start"
  say "Updated: $CURRENT_TAG -> ${LATEST_TAG}"
fi
