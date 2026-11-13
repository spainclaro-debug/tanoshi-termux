#!/data/data/com.termux/files/usr/bin/bash
#
# Tanoshi for Termux — reinstall / repair.
#
# Restores a working install in place: recreates missing runtime folders, the
# config file and the control command, resets permissions, clears stale plugin
# copies and restarts the server. Your data (library, accounts, history) is
# kept unless you ask for a reset.
#
# Usage:
#   bash reinstall.sh [options]
#
# Options:
#   --reset-data        move the existing data/ aside and start from scratch
#                       (recreates the admin account)
#   --port N            port to use if config.yml has to be recreated
#   --user NAME         admin username for a reset (default admin)
#   --password PASS     admin password for a reset (default tanoshi123)
#   --no-start          repair the files but do not start the server
#   --yes               assume yes for prompts
#   -h, --help          show this help

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

RESET_DATA=0
DO_START=1
PORT=""

while [ $# -gt 0 ]; do
  case "$1" in
    --reset-data) RESET_DATA=1; shift ;;
    --port) PORT="${2:?--port needs a value}"; shift 2 ;;
    --user) ADMIN_USER="${2:?--user needs a value}"; shift 2 ;;
    --password) ADMIN_PASS="${2:?--password needs a value}"; shift 2 ;;
    --no-start) DO_START=0; shift ;;
    --yes|-y) ASSUME_YES=1; shift ;;
    -h|--help) sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
done

: "${ADMIN_USER:=$DEFAULT_USER}"
: "${ADMIN_PASS:=$DEFAULT_PASSWORD}"

require_termux

# ---------------------------------------------------------------------------
# 1. Sanity checks
# ---------------------------------------------------------------------------

step "Tanoshi reinstall"
say "package:  $PKG_DIR"
say "version:  $(cat "$PKG_DIR/VERSION" 2>/dev/null || echo unknown)"

[ -f "$PKG_DIR/lib/common.sh" ] || die "this does not look like a Tanoshi package (lib/common.sh missing)"
[ -x "$PKG_DIR/bin/tanoshi" ] || die "server binary missing: $PKG_DIR/bin/tanoshi"
ok "package looks valid"

require_arch

# Remember the current port so a recreated config keeps it.
if [ -z "$PORT" ]; then
  PORT="$(svc_port)"
fi
say "port:     $PORT"

if [ "$RESET_DATA" = "1" ]; then
  say ""
  warn "reset requested: the current library and accounts will be moved aside"
  say "They stay on disk as data.old-<timestamp> so nothing is destroyed."
  if ! confirm "Continue with the data reset?" no; then
    die "aborted"
  fi
fi

# ---------------------------------------------------------------------------
# 2. Stop the server
# ---------------------------------------------------------------------------

step "Stopping server"
svc_stop

# ---------------------------------------------------------------------------
# 3. Back up the small, irreplaceable files
# ---------------------------------------------------------------------------

step "Backing up config and database"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="$PKG_DIR/backups/reinstall-$STAMP"
mkdir -p "$BACKUP_DIR"
DATA_DIR="$(data_dir)"
for item in config.yml tanoshi.db; do
  if [ -f "$DATA_DIR/$item" ]; then
    cp -a "$DATA_DIR/$item" "$BACKUP_DIR/" 2>/dev/null || true
  fi
done
ok "backup: $BACKUP_DIR"

# ---------------------------------------------------------------------------
# 4. Optional data reset
# ---------------------------------------------------------------------------

if [ "$RESET_DATA" = "1" ]; then
  step "Moving old data aside"
  if [ -d "$DATA_DIR" ]; then
    OLD="$PKG_DIR/data.old-$STAMP"
    mv "$DATA_DIR" "$OLD"
    ok "previous data: $OLD"
  fi
fi

# ---------------------------------------------------------------------------
# 5. Repair the runtime layout
# ---------------------------------------------------------------------------

step "Repairing runtime layout"
ensure_data_dirs
write_config "$PORT"
ensure_secret_length

step "Checking binary and plugins"
chmod 700 "$PKG_DIR/bin/tanoshi"
[ -x "$PKG_DIR/bin/tanoshi" ] || die "could not make $PKG_DIR/bin/tanoshi executable"
ok "server binary executable"

# Remove staged plugin copies left by an earlier run (runtime scratch files).
find "$PKG_DIR/plugins" -maxdepth 1 -name '.tanoshi-staged-*.so' -delete 2>/dev/null || true
chmod 700 "$PKG_DIR/plugins"/*.so 2>/dev/null || true
PLUGIN_COUNT="$(svc_count_plugin_files)"
[ "$PLUGIN_COUNT" -gt 0 ] || warn "no plugins found in $PKG_DIR/plugins"
ok "$PLUGIN_COUNT plugin(s) present"

step "Installing control command"
install_control_command

# ---------------------------------------------------------------------------
# 6. Start and verify
# ---------------------------------------------------------------------------

if [ "$DO_START" = "0" ]; then
  say ""
  say "Skipped start (--no-start). Later: tanoshi-ctl start"
else
  step "Starting server"
  svc_start || die "server failed to start; check $(svc_logfile)"

  report_plugin_health || true

  if [ "$RESET_DATA" = "1" ]; then
    step "Recreating the admin account"
    ensure_admin_user "$ADMIN_USER" "$ADMIN_PASS"
  fi
fi

# ---------------------------------------------------------------------------
# 7. Summary
# ---------------------------------------------------------------------------

step "Done"
cat <<EOF
Folder:    $PKG_DIR
Data:      $DATA_DIR
Config:    $DATA_DIR/config.yml
Backup:    $BACKUP_DIR
Web UI:    http://127.0.0.1:$(svc_port)
Log:       $(svc_logfile)

Login:     $ADMIN_USER / $ADMIN_PASS
EOF

if [ "$RESET_DATA" = "1" ]; then
  say ""
  say "Old data kept at: $PKG_DIR/data.old-$STAMP"
fi

if svc_running; then
  ok "server is running"
else
  say ""
  say "Server is not running. Start it with: tanoshi-ctl start"
fi
