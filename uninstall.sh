#!/data/data/com.termux/files/usr/bin/bash
#
# Tanoshi for Termux — uninstall.
#
# Stops the server, removes the control command from PATH, and deletes the
# package folder. Your data is backed up first by default.
#
# Usage:
#   bash uninstall.sh [options]
#
# Options:
#   --keep-data     delete the program but keep data/ (library, accounts,
#                   history) in place so a later install can reuse it
#   --no-backup     do not create a data backup before removing
#   --yes           assume yes for prompts
#   -h, --help      show this help

set -euo pipefail

# ---------------------------------------------------------------------------
# Re-execute from a temporary copy.
#
# bash reads a script as it runs, so deleting the folder this script lives in
# mid-execution would break it. Copy ourselves somewhere else first and pass
# the real package path through the environment.
# ---------------------------------------------------------------------------

if [ "${TANOSHI_UNINSTALL_REEXEC:-}" != "1" ]; then
  _SELF="${BASH_SOURCE[0]:-$0}"
  while [ -L "$_SELF" ]; do
    _link="$(readlink "$_SELF")"
    case "$_link" in
      /*) _SELF="$_link" ;;
      *)  _SELF="$(dirname "$_SELF")/$_link" ;;
    esac
  done
  _REAL_PKG="$(cd "$(dirname "$_SELF")" && pwd -P)"

  _TMP_BASE="${TMPDIR:-${PREFIX:-/data/data/com.termux/files/usr}/tmp}"
  mkdir -p "$_TMP_BASE" 2>/dev/null || _TMP_BASE="$HOME"
  _TMP="$(mktemp -d "$_TMP_BASE/tanoshi-uninstall.XXXXXX")" || {
    echo "error: could not create a temporary directory" >&2
    exit 1
  }
  mkdir -p "$_TMP/lib"
  cp "$_SELF" "$_TMP/uninstall.sh"
  cp "$_REAL_PKG/lib/common.sh" "$_TMP/lib/common.sh" 2>/dev/null || true

  TANOSHI_UNINSTALL_REEXEC=1 \
  TANOSHI_UNINSTALL_TMP="$_TMP" \
  TANOSHI_PKG_DIR="$_REAL_PKG" \
    exec bash "$_TMP/uninstall.sh" "$@"
fi

PKG_DIR="${TANOSHI_PKG_DIR:?internal error: package path not set}"
UNINSTALL_TMP="${TANOSHI_UNINSTALL_TMP:-}"
# shellcheck source=/dev/null
. "$PKG_DIR/lib/common.sh"

# Clean up the temporary copy on the way out.
if [ -n "$UNINSTALL_TMP" ]; then
  trap 'rm -rf "$UNINSTALL_TMP" 2>/dev/null || true' EXIT
fi

KEEP_DATA=0
DO_BACKUP=1

while [ $# -gt 0 ]; do
  case "$1" in
    --keep-data) KEEP_DATA=1; shift ;;
    --no-backup) DO_BACKUP=0; shift ;;
    --yes|-y) ASSUME_YES=1; shift ;;
    -h|--help) sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
done

require_termux

# ---------------------------------------------------------------------------
# 1. Safety checks
# ---------------------------------------------------------------------------

step "Tanoshi uninstall"
say "package:  $PKG_DIR"

# Refuse to run against anything that is not clearly a Tanoshi folder.
case "$PKG_DIR" in
  "/"|"$HOME"|"$HOME/"|"${PREFIX:-/nonexistent}"*)
    die "refusing to uninstall from a protected path: $PKG_DIR" ;;
esac
if [ ! -f "$PKG_DIR/install.sh" ] && [ ! -x "$PKG_DIR/bin/tanoshi" ]; then
  die "this does not look like a Tanoshi folder: $PKG_DIR"
fi
ok "folder verified"

DATA_DIR="$(data_dir)"
HAS_DATA=0
if [ -f "$DATA_DIR/tanoshi.db" ] || [ -d "$DATA_DIR/manga" ]; then
  HAS_DATA=1
fi

# ---------------------------------------------------------------------------
# 2. Show what will happen
# ---------------------------------------------------------------------------

say ""
say "This will:"
say "  - stop the Tanoshi server (if running)"
say "  - remove the control command $PREFIX/bin/tanoshi-ctl"
if [ "$KEEP_DATA" = "1" ]; then
  say "  - delete the program files, KEEPING $DATA_DIR"
else
  say "  - delete the whole folder $PKG_DIR"
  if [ "$HAS_DATA" = "1" ]; then
    if [ "$DO_BACKUP" = "1" ]; then
      say "    (your data is backed up to ~/ first)"
    else
      warn "    --no-backup was given: YOUR DATA WILL BE DELETED"
    fi
  fi
fi
say ""

if [ "$KEEP_DATA" = "0" ] && [ "$HAS_DATA" = "1" ] && [ "$DO_BACKUP" = "0" ]; then
  warn "This deletes your library, accounts and reading history permanently."
  if ! confirm "Really delete everything?" no; then
    die "aborted"
  fi
else
  if ! confirm "Continue?" no; then
    die "aborted"
  fi
fi

# ---------------------------------------------------------------------------
# 3. Stop the server
# ---------------------------------------------------------------------------

step "Stopping server"
svc_stop

# ---------------------------------------------------------------------------
# 4. Back up data
# ---------------------------------------------------------------------------

BACKUP_FILE=""
if [ "$DO_BACKUP" = "1" ] && [ -d "$DATA_DIR" ]; then
  step "Backing up data"
  STAMP="$(date +%Y%m%d-%H%M%S)"
  BACKUP_FILE="$HOME/tanoshi-backup-$STAMP.tar.gz"
  if tar -czf "$BACKUP_FILE" -C "$PKG_DIR" data 2>/dev/null; then
    ok "backup: $BACKUP_FILE ($(du -h "$BACKUP_FILE" | cut -f1))"
  else
    warn "backup failed; continuing without one"
    BACKUP_FILE=""
  fi
fi

# ---------------------------------------------------------------------------
# 5. Remove the control command
# ---------------------------------------------------------------------------

step "Removing control command"
if [ -L "$PREFIX/bin/tanoshi-ctl" ] || [ -f "$PREFIX/bin/tanoshi-ctl" ]; then
  rm -f "$PREFIX/bin/tanoshi-ctl"
  ok "removed $PREFIX/bin/tanoshi-ctl"
else
  ok "control command not present"
fi

# Leave the folder before deleting it, so the shell does not sit on a
# vanished working directory.
cd "$HOME" || true

# ---------------------------------------------------------------------------
# 6. Delete files
# ---------------------------------------------------------------------------

if [ "$KEEP_DATA" = "1" ]; then
  step "Removing program files (keeping data)"
  for entry in bin plugins src extensions patches vendor lib \
               install.sh update.sh reinstall.sh uninstall.sh \
               README.md VERSION LIB_VERSION tanoshi.sh; do
    [ -e "$PKG_DIR/$entry" ] && rm -rf "${PKG_DIR:?}/$entry"
  done
  # Stale staged plugin copies live under plugins/, already removed above.
  ok "program files removed"
  say ""
  say "Kept: $DATA_DIR"
  say "Reinstall later with the package's install.sh and this data will be reused."
else
  step "Removing $PKG_DIR"
  rm -rf "${PKG_DIR:?}"
  [ -d "$PKG_DIR" ] && die "could not fully remove $PKG_DIR" || true
  ok "removed"
fi

# ---------------------------------------------------------------------------
# 7. Summary
# ---------------------------------------------------------------------------

step "Done"
say "Tanoshi has been uninstalled."
if [ -n "$BACKUP_FILE" ]; then
  say ""
  say "Your data was backed up to:"
  say "  $BACKUP_FILE"
  say ""
  say "Restore it by extracting into a fresh install:"
  say "  tar -xzf \"$BACKUP_FILE\" -C /path/to/tanoshi"
fi
if [ "$KEEP_DATA" = "1" ]; then
  say ""
  say "Data kept at: $DATA_DIR"
fi
