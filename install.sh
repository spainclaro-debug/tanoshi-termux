#!/data/data/com.termux/files/usr/bin/bash
#
# Tanoshi for Termux — installer / setup script.
#
# Installs a prebuilt Tanoshi server (or builds it from the bundled source) and
# sets it up as a self-contained folder with its own data directory.
#
# Usage:
#   bash install.sh [options]
#
# Options:
#   --port N          HTTP port (default 8080)
#   --user NAME       admin username (default admin)
#   --password PASS   admin password (default tanoshi123)
#   --from-source     build everything locally instead of using the bundled
#                     binaries (needs Rust toolchain; slow first time)
#   --with-build-tools  also install the Rust/wasm toolchain so update.sh can
#                     rebuild later (implies nothing else)
#   --no-start        set everything up but do not start the server
#   --no-admin        do not create the initial admin user
#   --yes             assume yes for prompts
#   -h, --help        show this help

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

PORT="$DEFAULT_PORT"
ADMIN_USER="$DEFAULT_USER"
ADMIN_PASS="$DEFAULT_PASSWORD"
FROM_SOURCE=0
WITH_BUILD_TOOLS=0
DO_START=1
DO_ADMIN=1

usage() { sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT="${2:?--port needs a value}"; shift 2 ;;
    --user) ADMIN_USER="${2:?--user needs a value}"; shift 2 ;;
    --password) ADMIN_PASS="${2:?--password needs a value}"; shift 2 ;;
    --from-source) FROM_SOURCE=1; shift ;;
    --with-build-tools) WITH_BUILD_TOOLS=1; shift ;;
    --no-start) DO_START=0; shift ;;
    --no-admin) DO_ADMIN=0; shift ;;
    --yes|-y) ASSUME_YES=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
done

require_termux

# ---------------------------------------------------------------------------
# 1. Sanity checks
# ---------------------------------------------------------------------------

step "Tanoshi installer"
say "package:  $PKG_DIR"
say "version:  $(cat "$PKG_DIR/VERSION" 2>/dev/null || echo unknown)"

if [ "$FROM_SOURCE" = "0" ]; then
  require_arch
  [ -x "$PKG_DIR/bin/tanoshi" ] || die "bundled binary missing: $PKG_DIR/bin/tanoshi"
  if [ -z "$(ls -A "$PKG_DIR/plugins" 2>/dev/null)" ]; then
    warn "no plugins found in $PKG_DIR/plugins — Tanoshi will start with no sources"
  fi
  ok "bundled binaries present"
else
  say "mode:     build from source"
fi

# ---------------------------------------------------------------------------
# 2. Packages
# ---------------------------------------------------------------------------

ensure_base_packages

if [ "$FROM_SOURCE" = "1" ] || [ "$WITH_BUILD_TOOLS" = "1" ]; then
  ensure_build_toolchain
else
  say ""
  say "Skipping build toolchain (prebuilt mode). Run with --with-build-tools"
  say "if you want update.sh to rebuild locally later."
fi

# ---------------------------------------------------------------------------
# 3. Build (optional)
# ---------------------------------------------------------------------------

if [ "$FROM_SOURCE" = "1" ]; then
  step "Building Tanoshi from source"
  BUILD_SCRIPT="$PKG_DIR/lib/build.sh"
  [ -f "$BUILD_SCRIPT" ] || die "missing $BUILD_SCRIPT"
  # Prefer the newest upstream release; fall back to the bundled version.
  REF="$(latest_release_tag 2>/dev/null || true)"
  [ -n "$REF" ] || REF="$(cat "$PKG_DIR/VERSION")"
  say "building ref: $REF"
  PKG_DIR="$PKG_DIR" bash "$BUILD_SCRIPT" --install-to "$PKG_DIR" --ref "$REF"
  ok "build complete"
fi

# 4. Runtime layout, config and control command
# ---------------------------------------------------------------------------

step "Creating data directory"
ensure_data_dirs

step "Writing config.yml"
write_config "$PORT"
ensure_secret_length

step "Installing control command"
install_control_command

# ---------------------------------------------------------------------------
# 7. Start + initial admin user
# ---------------------------------------------------------------------------

if [ "$DO_START" = "1" ]; then
  step "Starting server"
  svc_start || die "server failed to start; check $(svc_logfile)"

  report_plugin_health || true

  if [ "$DO_ADMIN" = "1" ]; then
    step "Setting up the admin account"
    ensure_admin_user "$ADMIN_USER" "$ADMIN_PASS"
  fi
else
  say ""
  say "Skipped start (--no-start). Later: tanoshi-ctl start"
fi

# ---------------------------------------------------------------------------
# 8. Summary
# ---------------------------------------------------------------------------

PORT_NOW="$(svc_port)"
step "Done"
cat <<EOF
Folder:    $PKG_DIR
Data:      $(data_dir)
Config:    $(data_dir)/config.yml
Port:      $PORT_NOW
Web UI:    http://127.0.0.1:$PORT_NOW
Log:       $(svc_logfile)

Login:     $ADMIN_USER / $ADMIN_PASS   (change this in the UI settings)

Commands:
  tanoshi-ctl start|stop|restart|status|logs|url
  bash update.sh            update to the latest upstream release
  bash reinstall.sh         repair this install (keeps your data)
  bash uninstall.sh         remove Tanoshi
EOF

if svc_running; then
  ok "server is running"
else
  say ""
  say "Server is not running. Start it with: tanoshi-ctl start"
fi
