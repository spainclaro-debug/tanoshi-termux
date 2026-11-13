#!/data/data/com.termux/files/usr/bin/bash
#
# Tanoshi for Termux — updater.
#
# Checks the upstream repo for the newest release and, if it is newer than the
# installed version, rebuilds Tanoshi for Android and swaps it in.
#
# Upstream publishes no Android/Termux binary (only glibc Linux, Windows and
# macOS), so "updating" means rebuilding from source with the Android patches
# in patches/. First run installs the build toolchain if it is missing.
#
# Usage:
#   bash update.sh [options]
#
# Options:
#   --check          only report whether an update exists; change nothing
#   --force          rebuild even when already on the latest version
#   --server-only    rebuild the server but never touch the extensions
#   --with-extensions  also rebuild all extension plugins
#   --rollback       restore the most recent backup
#   --yes            assume yes for prompts
#   -h, --help       show this help

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

CHECK_ONLY=0
FORCE=0
SERVER_ONLY=0
WITH_EXTENSIONS=0
ROLLBACK=0

while [ $# -gt 0 ]; do
  case "$1" in
    --check) CHECK_ONLY=1; shift ;;
    --force) FORCE=1; shift ;;
    --server-only) SERVER_ONLY=1; shift ;;
    --with-extensions) WITH_EXTENSIONS=1; shift ;;
    --rollback) ROLLBACK=1; shift ;;
    --yes|-y) ASSUME_YES=1; shift ;;
    -h|--help) sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
done

require_termux
[ -f "$PKG_DIR/lib/build.sh" ] || die "missing $PKG_DIR/lib/build.sh"

BACKUP_ROOT="$PKG_DIR/backups"
CURRENT_VERSION="$(cat "$PKG_DIR/VERSION" 2>/dev/null || echo "0.0.0")"
CURRENT_LIB_VERSION="$(cat "$PKG_DIR/LIB_VERSION" 2>/dev/null || echo "")"

# ---------------------------------------------------------------------------
# Rollback
# ---------------------------------------------------------------------------
if [ "$ROLLBACK" = "1" ]; then
  step "Rolling back"
  [ -d "$BACKUP_ROOT" ] || die "no backups found in $BACKUP_ROOT"
  LATEST="$(ls -1d "$BACKUP_ROOT"/*/ 2>/dev/null | sort | tail -1)"
  [ -n "$LATEST" ] || die "no backups found in $BACKUP_ROOT"
  say "restoring: $LATEST"
  if ! confirm "Replace the current install with this backup?" yes; then
    die "aborted"
  fi
  svc_stop || true
  [ -d "$LATEST/bin" ] && cp -a "$LATEST/bin/." "$PKG_DIR/bin/"
  [ -d "$LATEST/plugins" ] && cp -a "$LATEST/plugins/." "$PKG_DIR/plugins/"
  [ -f "$LATEST/VERSION" ] && cp -f "$LATEST/VERSION" "$PKG_DIR/VERSION"
  [ -f "$LATEST/LIB_VERSION" ] && cp -f "$LATEST/LIB_VERSION" "$PKG_DIR/LIB_VERSION"
  ok "restored version $(cat "$PKG_DIR/VERSION")"
  svc_start || true
  exit 0
fi

# ---------------------------------------------------------------------------
# 1. Which version is upstream?
# ---------------------------------------------------------------------------
step "Checking for updates"
say "installed: $CURRENT_VERSION (tanoshi-lib ${CURRENT_LIB_VERSION:-unknown})"

LATEST_TAG="$(latest_release_tag)" || die "could not query GitHub for the latest release"
[ -n "$LATEST_TAG" ] || die "GitHub returned an empty release tag"
LATEST_VERSION="${LATEST_TAG#v}"
say "upstream:  $LATEST_VERSION ($LATEST_TAG)"

if [ "$LATEST_VERSION" = "$CURRENT_VERSION" ] && [ "$FORCE" = "0" ]; then
  ok "already on the latest version"
  if [ "$CHECK_ONLY" = "1" ]; then exit 0; fi
  if ! confirm "Rebuild the current version anyway?"; then
    exit 0
  fi
  FORCE=1
fi

if [ "$CHECK_ONLY" = "1" ]; then
  say ""
  say "An update is available: $CURRENT_VERSION -> $LATEST_VERSION"
  say "Run 'bash update.sh' to install it."
  exit 0
fi

say ""
say "This will rebuild Tanoshi $LATEST_VERSION from source for Android."
say "A first-time build can take 20+ minutes."
if ! confirm "Continue?" yes; then
  die "aborted"
fi

# ---------------------------------------------------------------------------
# 2. Toolchain
# ---------------------------------------------------------------------------
ensure_base_packages
ensure_build_toolchain

# ---------------------------------------------------------------------------
# 3. Backup
# ---------------------------------------------------------------------------
step "Backing up the current install"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="$BACKUP_ROOT/$STAMP"
mkdir -p "$BACKUP_DIR/bin" "$BACKUP_DIR/plugins"
[ -x "$PKG_DIR/bin/tanoshi" ] && cp -a "$PKG_DIR/bin/tanoshi" "$BACKUP_DIR/bin/"
if compgen -G "$PKG_DIR/plugins/*.so" >/dev/null; then
  cp -a "$PKG_DIR"/plugins/*.so "$BACKUP_DIR/plugins/"
fi
cp -f "$PKG_DIR/VERSION" "$BACKUP_DIR/VERSION" 2>/dev/null || true
[ -n "$CURRENT_LIB_VERSION" ] && printf '%s\n' "$CURRENT_LIB_VERSION" >"$BACKUP_DIR/LIB_VERSION"
ok "backup: $BACKUP_DIR"
# Keep the five most recent backups.
ls -1d "$BACKUP_ROOT"/*/ 2>/dev/null | sort | head -n -5 | while read -r old; do
  [ -n "$old" ] && rm -rf "$old"
done

# ---------------------------------------------------------------------------
# 4. Build
# ---------------------------------------------------------------------------
WAS_RUNNING=0
if svc_running; then
  WAS_RUNNING=1
  step "Stopping server"
  svc_stop
fi

BUILD_ARGS=(--ref "$LATEST_TAG" --install-to "$PKG_DIR")
BUILD_OUT=""
set +e
BUILD_OUT="$(PKG_DIR="$PKG_DIR" bash "$PKG_DIR/lib/build.sh" "${BUILD_ARGS[@]}" 2>&1)"
BUILD_RC=$?
set -e

printf '%s\n' "$BUILD_OUT" | tail -12

if [ "$BUILD_RC" -ne 0 ]; then
  warn "build failed"
  if [ "$WAS_RUNNING" = "1" ]; then
    svc_start || true
  fi
  die "update aborted; the previous install is untouched (backup: $BACKUP_DIR)"
fi

NEW_VERSION="$(printf '%s\n' "$BUILD_OUT" | sed -n 's/^BUILT_VERSION=//p' | tail -1)"
NEW_LIB_VERSION="$(printf '%s\n' "$BUILD_OUT" | sed -n 's/^BUILT_LIB_VERSION=//p' | tail -1)"
[ -n "$NEW_VERSION" ] || NEW_VERSION="$LATEST_VERSION"

# ---------------------------------------------------------------------------
# 5. Extensions
# ---------------------------------------------------------------------------
# Plugins embed tanoshi-lib's version and refuse to load on a mismatch, so a
# lib-version change forces an extension rebuild.
NEED_EXT=0
if [ "$WITH_EXTENSIONS" = "1" ]; then
  NEED_EXT=1
elif [ "$SERVER_ONLY" = "0" ] && [ -n "$NEW_LIB_VERSION" ] \
     && [ "$NEW_LIB_VERSION" != "$CURRENT_LIB_VERSION" ]; then
  NEED_EXT=1
  warn "tanoshi-lib changed: $CURRENT_LIB_VERSION -> $NEW_LIB_VERSION"
  warn "extensions must be rebuilt to keep loading"
fi

if [ "$NEED_EXT" = "1" ]; then
  step "Rebuilding extensions"
  set +e
  # Extensions-only: the server was already built above.
  EXT_OUT="$(PKG_DIR="$PKG_DIR" bash "$PKG_DIR/lib/build.sh" --extensions-only \
             --install-to "$PKG_DIR" 2>&1)"
  EXT_RC=$?
  set -e
  printf '%s\n' "$EXT_OUT" | tail -8
  if [ "$EXT_RC" -ne 0 ]; then
    warn "extension rebuild failed — plugins may be incompatible with the new server"
    warn "restore the previous plugins with: bash update.sh --rollback"
  else
    ok "extensions rebuilt"
  fi
fi

printf '%s\n' "$NEW_VERSION" >"$PKG_DIR/VERSION"
[ -n "$NEW_LIB_VERSION" ] && printf '%s\n' "$NEW_LIB_VERSION" >"$PKG_DIR/LIB_VERSION"

# ---------------------------------------------------------------------------
# 6. Restart + verify
# ---------------------------------------------------------------------------
step "Starting server"
if svc_start; then
  PORT_NOW="$(svc_port)"
  STATUS="$(gql '{ serverStatus { activated version } }')"
  RUNNING_VERSION="$(json_get "$STATUS" 'data.serverStatus.version' 2>/dev/null || true)"
  if [ "$RUNNING_VERSION" = "$NEW_VERSION" ]; then
    ok "running version $RUNNING_VERSION"
  else
    warn "server reports version '${RUNNING_VERSION:-unknown}', expected '$NEW_VERSION'"
  fi

  # Count plugin files vs the ones the server reported loading at startup.
  # (installedSources needs a token, so read the startup log instead.)
  PLUGIN_FILES="$(svc_count_plugin_files)"
  LOADED="$(svc_count_loaded_plugins)"
  say "plugins on disk: $PLUGIN_FILES | loaded by server: ${LOADED:-0}"
  if [ "${LOADED:-0}" -lt "$PLUGIN_FILES" ]; then
    warn "some plugins did not load — likely a tanoshi-lib version mismatch; try:"
    warn "  bash update.sh --with-extensions --force"
  fi

  say ""
  say "Updated: $CURRENT_VERSION -> $NEW_VERSION"
  say "Web UI:  http://127.0.0.1:$PORT_NOW"
else
  warn "server did not start; roll back with: bash update.sh --rollback"
  exit 1
fi
