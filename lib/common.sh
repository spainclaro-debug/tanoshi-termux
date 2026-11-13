#!/data/data/com.termux/files/usr/bin/bash
# Shared helpers for Tanoshi on Termux (sourced by install.sh and update.sh).
# Not meant to be executed directly.

set -euo pipefail

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

UPSTREAM_REPO="luigi311/tanoshi"
EXTENSIONS_REPO="luigi311/tanoshi-extensions"
GITHUB_API="https://api.github.com/repos"

# Plugin names shipped with the package (order = build order).
PLUGIN_NAMES=(mangadex guya manhwa18cc nhentai weebcentral atsumaru qiscans mangaball madaradex hentainexus)
# Plugins that come from this package's own Rust ports.
PORTED_PLUGINS=(atsumaru qiscans mangaball madaradex hentainexus)

DEFAULT_PORT=8080
DEFAULT_USER="admin"
DEFAULT_PASSWORD="tanoshi123"

# Versions that must match the upstream extension workspace pin.
WASM_BINDGEN_VERSION="${WASM_BINDGEN_VERSION:-0.2.129}"
SASS_VERSION="${SASS_VERSION:-1.106.1}"
WASM_OPT_VERSION="133"

# ---------------------------------------------------------------------------
# Output helpers
# ---------------------------------------------------------------------------

if [ -t 1 ]; then
  C_RESET=$'\033[0m'; C_RED=$'\033[31m'; C_GREEN=$'\033[32m'
  C_YELLOW=$'\033[33m'; C_BLUE=$'\033[34m'; C_BOLD=$'\033[1m'
else
  C_RESET=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""; C_BOLD=""
fi

say()  { printf '%s\n' "$*"; }
step() { printf '\n%s==> %s%s\n' "$C_BLUE$C_BOLD" "$*" "$C_RESET"; }
ok()   { printf '%s  ok%s  %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '%s warn%s  %s\n' "$C_YELLOW" "$C_RESET" "$*" >&2; }
die()  { printf '\n%serror%s %s\n' "$C_RED$C_BOLD" "$C_RESET" "$*" >&2; exit 1; }

confirm() {
  # confirm "question" [default-yes]
  local prompt="$1" default="${2:-no}" reply
  if [ "${ASSUME_YES:-0}" = "1" ]; then
    return 0
  fi
  if [ ! -t 0 ]; then
    # Non-interactive: accept the default.
    [ "$default" = "yes" ]
    return $?
  fi
  local hint="[y/N]"
  [ "$default" = "yes" ] && hint="[Y/n]"
  printf '%s %s ' "$prompt" "$hint"
  read -r reply
  reply="${reply:-$default}"
  case "$reply" in
    y|Y|yes|YES) return 0 ;;
    *) return 1 ;;
  esac
}

# ---------------------------------------------------------------------------
# Environment
# ---------------------------------------------------------------------------

require_termux() {
  if [ -z "${PREFIX:-}" ] || [ ! -d "/data/data/com.termux/files/usr" ]; then
    die "This script must run inside Termux on Android."
  fi
}

require_arch() {
  local arch
  arch="$(uname -m)"
  case "$arch" in
    aarch64|arm64) : ;;
    *)
      die "The bundled binaries are aarch64 (arm64) only; this device is '$arch'.
Rebuild from source instead: bash install.sh --from-source"
      ;;
  esac
}

# ---------------------------------------------------------------------------
# Service control (start/stop/status) against the installed runtime
# ---------------------------------------------------------------------------

# These are defined here so install.sh and update.sh share identical logic.
# The runtime layout is created by install.sh:
#   $PKG_DIR/data/config.yml   (TANOSHI_HOME)
#   $PKG_DIR/bin/tanoshi       (server binary)
#   $PKG_DIR/plugins/*.so

svc_pidfile() { printf '%s' "$PKG_DIR/data/tanoshi.pid"; }
svc_logfile() { printf '%s' "$PKG_DIR/data/tanoshi.log"; }

svc_pid() {
  local pidfile; pidfile="$(svc_pidfile)"
  [ -f "$pidfile" ] || return 1
  local pid; pid="$(cat "$pidfile" 2>/dev/null || true)"
  [ -n "$pid" ] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  printf '%s' "$pid"
}

svc_running() { svc_pid >/dev/null 2>&1; }

svc_port() {
  local config="$PKG_DIR/data/config.yml"
  [ -f "$config" ] || { printf '%s' "$DEFAULT_PORT"; return; }
  # Accept both YAML styles the port can appear in:
  #   port: 8080            (this package's installer)
  #   'port':\n  8080       (as written by the server itself)
  local port
  port="$(awk '
    /^['"'"'"]?port['"'"'"]?[[:space:]]*:/ {
      value = $0
      sub(/^[^:]*:[[:space:]]*/, "", value)
      gsub(/['"'"'"[:space:]]/, "", value)
      if (value ~ /^[0-9]+$/) { print value; exit }
      if (getline) {
        gsub(/[^0-9]/, "", $0)
        if ($0 ~ /^[0-9]+$/) { print $0; exit }
      }
    }
  ' "$config")"
  printf '%s' "${port:-$DEFAULT_PORT}"
}

svc_start() {
  local bin="$PKG_DIR/bin/tanoshi" home="$PKG_DIR/data" log; log="$(svc_logfile)"
  [ -x "$bin" ] || die "server binary not found at $bin"
  mkdir -p "$home"
  if svc_running; then
    ok "already running (pid $(svc_pid))"
    return 0
  fi
  # Detach fully: the process must outlive this shell.
  (
    cd "$home" || exit 1
    export TANOSHI_HOME="$home"
    export TANOSHI_LOG="${TANOSHI_LOG:-info}"
    nohup "$bin" >>"$log" 2>&1 &
    printf '%s' "$!" >"$(svc_pidfile)"
  )
  # Wait for the port to answer.
  local port; port="$(svc_port)"
  local i=0
  while [ $i -lt 60 ]; do
    if curl -s -o /dev/null --max-time 2 "http://127.0.0.1:${port}/" 2>/dev/null; then
      ok "listening on http://127.0.0.1:${port}"
      return 0
    fi
    sleep 1; i=$((i + 1))
  done
  warn "server did not answer on port $port within 60s; see $log"
  return 1
}

svc_stop() {
  local pid
  if ! pid="$(svc_pid)"; then
    ok "not running"
    rm -f "$(svc_pidfile)"
    return 0
  fi
  kill "$pid" 2>/dev/null || true
  local i=0
  while [ $i -lt 20 ]; do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1; i=$((i + 1))
  done
  kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
  rm -f "$(svc_pidfile)"
  ok "stopped"
}

svc_status() {
  local port; port="$(svc_port)"
  if svc_running; then
    say "status:  running (pid $(svc_pid))"
    say "url:     http://127.0.0.1:${port}"
    say "web ui:  $(curl -s -o /dev/null --max-time 3 -w '%{http_code}' "http://127.0.0.1:${port}/" 2>/dev/null || echo unreachable)"
    say "log:     $(svc_logfile)"
    return 0
  fi
  say "status:  stopped"
  return 1
}

# Number of extensions the server loaded during its most recent startup.
# The log is appended across restarts, so count from the last "loading
# extensions" line onwards.
svc_count_loaded_plugins() {
  local log; log="$(svc_logfile)"
  [ -f "$log" ] || { printf '0'; return; }
  awk '
    /loading extensions from / { n = 0 }
    /extension::manager\] load / { n++ }
    END { print n + 0 }
  ' "$log"
}

# Number of plugin files shipped in the package.
# Staged copies (".tanoshi-staged-*") are runtime scratch files, not plugins.
svc_count_plugin_files() {
  find "$PKG_DIR/plugins" -maxdepth 1 -name '*.so' -not -name '.*' 2>/dev/null | wc -l | tr -d ' '
}

# ---------------------------------------------------------------------------
# Installation pieces (shared by install.sh and reinstall.sh)
# ---------------------------------------------------------------------------

DATA_DIR_NAME="data"

data_dir() { printf '%s' "$PKG_DIR/$DATA_DIR_NAME"; }

# Create the runtime folders and point data/plugins at the package plugins.
ensure_data_dirs() {
  local d; d="$(data_dir)"
  mkdir -p "$d"/{downloads,cache,manga}
  if [ -d "$d/plugins" ] && [ ! -L "$d/plugins" ]; then
    # May hold a real directory from an older layout; only replace when empty.
    rmdir "$d/plugins" 2>/dev/null || true
  fi
  ln -sfn "$PKG_DIR/plugins" "$d/plugins"
  ok "data directory: $d"
}

# Write data/config.yml if missing. Absolute paths only: Tanoshi does not
# expand ~ or environment variables in this file.
write_config() {
  local port="${1:-$DEFAULT_PORT}"
  local d config; d="$(data_dir)"; config="$d/config.yml"

  if [ -f "$config" ]; then
    ok "keeping existing config: $config"
    return 0
  fi

  local secret
  secret="$(python3 -c 'import secrets,string; print("".join(secrets.choice(string.ascii_letters+string.digits) for _ in range(16)))')"
  local old_umask; old_umask="$(umask)"
  umask 077
  cat >"$config" <<EOF
base_url: null
port: $port
database_path: "$d/tanoshi.db"
create_database: true
secret: "$secret"
update_interval: 3600
max_concurrent_update_sources: 2
auto_download_chapters: false
plugin_path: "$PKG_DIR/plugins"
extension:
  max_concurrent_calls_per_source: 8
  metadata_timeout_secs: 30
  image_timeout_secs: 120
local_path: "$d/manga"
download_path: "$d/downloads"
cache_path: "$d/cache"
enable_playground: false
telegram: null
pushover: null
gotify: null
myanimelist: null
anilist: null
EOF
  chmod 600 "$config"
  umask "$old_umask"
  ok "wrote config: $config (port $port)"
}

# Read the secret from a config file (YAML scalar, quoted or not).
config_secret() {
  local config="$1"
  [ -f "$config" ] || return 0
  awk '
    /^['"'"'"]?secret['"'"'"]?[[:space:]]*:/ {
      v = $0
      sub(/^[^:]*:[[:space:]]*/, "", v)
      gsub(/['"'"'"[:space:]]/, "", v)
      print v
      exit
    }
  ' "$config"
}

# Tanoshi encrypts image URLs with AES-128-CBC, which needs a key of exactly
# 16 bytes. Upstream generates 16 characters; any other length makes every
# coverUrl resolver fail with "invalid key length", which the web UI reports as
# "Fetch manga from source failed: error decoding response body".
ensure_secret_length() {
  local config="${1:-$(data_dir)/config.yml}"
  [ -f "$config" ] || return 0

  local secret
  secret="$(config_secret "$config")"
  [ -n "$secret" ] || { warn "no secret found in $config"; return 0; }
  if [ "${#secret}" -eq 16 ]; then
    return 0
  fi

  local new
  new="$(python3 -c 'import secrets,string; print("".join(secrets.choice(string.ascii_letters+string.digits) for _ in range(16)))')"
  python3 "$PKG_DIR/lib/repair_secret.py" "$config" "$new"
  chmod 600 "$config" 2>/dev/null || true
  warn "secret was ${#secret} bytes but AES-128 needs 16; replaced it"
  warn "existing logins are now invalid — log in again"
  ok "secret repaired"
}

# Write the control script and expose it on PATH as `tanoshi-ctl`.
install_control_command() {
  cat >"$PKG_DIR/tanoshi.sh" <<'CONTROL'
#!/data/data/com.termux/files/usr/bin/bash
# Control the local Tanoshi server: start | stop | restart | status | logs | url
set -euo pipefail

# This script is normally invoked through the symlink $PREFIX/bin/tanoshi-ctl,
# so resolve symlinks first to find the real package folder.
SELF="${BASH_SOURCE[0]:-$0}"
while [ -L "$SELF" ]; do
  _link="$(readlink "$SELF")"
  case "$_link" in
    /*) SELF="$_link" ;;
    *)  SELF="$(dirname "$SELF")/$_link" ;;
  esac
done
PKG_DIR="$(cd "$(dirname "$SELF")" && pwd -P)"

# shellcheck source=lib/common.sh
. "$PKG_DIR/lib/common.sh"

case "${1:-status}" in
  start)   svc_start ;;
  stop)    svc_stop ;;
  restart) svc_stop; svc_start ;;
  status)  svc_status ;;
  logs)    tail -n "${2:-50}" -f "$(svc_logfile)" ;;
  url)     printf 'http://127.0.0.1:%s\n' "$(svc_port)" ;;
  *)       echo "usage: tanoshi.sh {start|stop|restart|status|logs|url}" >&2; exit 2 ;;
esac
CONTROL
  chmod +x "$PKG_DIR/tanoshi.sh"

  mkdir -p "$PREFIX/bin"
  ln -sfn "$PKG_DIR/tanoshi.sh" "$PREFIX/bin/tanoshi-ctl"
  ok "control command: tanoshi-ctl -> $PKG_DIR/tanoshi.sh"
}

# Create the first admin account if the server has no users yet.
ensure_admin_user() {
  local user="${1:-$DEFAULT_USER}" pass="${2:-$DEFAULT_PASSWORD}"
  local status activated
  status="$(gql '{ serverStatus { activated version } }')"
  activated="$(json_get "$status" 'data.serverStatus.activated' 2>/dev/null || true)"

  if [ "$activated" = "True" ] || [ "$activated" = "true" ]; then
    ok "server already has users; leaving accounts untouched"
    return 0
  fi

  local result
  result="$(gql "mutation { register(login: {username: \"$user\", password: \"$pass\"}, isAdmin: true) }")"
  if printf '%s' "$result" | grep -q '"register"'; then
    ok "created admin user '$user'"
  else
    warn "could not create admin automatically: $result"
    warn "open the web UI and register the first account there"
  fi
}

# Report how many plugins were loaded versus how many are shipped.
report_plugin_health() {
  local files loaded
  files="$(svc_count_plugin_files)"
  loaded="$(svc_count_loaded_plugins)"
  say "plugins on disk: $files | loaded by server: ${loaded:-0}"
  if [ "$files" -gt 0 ] && [ "${loaded:-0}" -lt "$files" ]; then
    warn "some plugins did not load (version mismatch?) — see $(svc_logfile)"
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------

gql() {
  # gql '<query>' [token]
  local query="$1" token="${2:-}" port; port="$(svc_port)"
  local auth=()
  [ -n "$token" ] && auth=(-H "Authorization: Bearer $token")
  python3 - "$port" "$query" "${token:-}" <<'PY'
import json, sys, urllib.request, urllib.error
port, query, token = sys.argv[1], sys.argv[2], sys.argv[3]
req = urllib.request.Request(
    f"http://127.0.0.1:{port}/graphql",
    data=json.dumps({"query": query}).encode(),
    headers={"Content-Type": "application/json"},
)
if token:
    req.add_header("Authorization", f"Bearer {token}")
try:
    print(urllib.request.urlopen(req, timeout=120).read().decode())
except urllib.error.HTTPError as e:
    print(e.read().decode())
except Exception as e:
    print(json.dumps({"error": str(e)}))
PY
}

json_get() {
  # json_get '<json>' 'dotted.path'
  python3 - "$1" "$2" <<'PY'
import json, sys
data = json.loads(sys.argv[1])
cur = data
for part in sys.argv[2].split('.'):
    if part.isdigit() and isinstance(cur, list):
        cur = cur[int(part)]
    elif isinstance(cur, dict):
        cur = cur.get(part)
    else:
        cur = None
    if cur is None:
        break
print('' if cur is None else cur)
PY
}

latest_release_tag() {
  python3 - "$UPSTREAM_REPO" <<'PY'
import json, sys, urllib.request
url = f"https://api.github.com/repos/{sys.argv[1]}/releases/latest"
req = urllib.request.Request(url, headers={"User-Agent": "tanoshi-termux-installer"})
try:
    data = json.load(urllib.request.urlopen(req, timeout=60))
    print(data.get("tag_name", ""))
except Exception as e:
    print(f"ERROR:{e}", file=sys.stderr)
    sys.exit(1)
PY
}

# ---------------------------------------------------------------------------
# Build toolchain
# ---------------------------------------------------------------------------

have() { command -v "$1" >/dev/null 2>&1; }

ensure_base_packages() {
  step "Installing Termux packages"
  # The server binary itself only needs libarchive (+ libc/libm/libdl); the
  # scripts also use python for JSON/HTTP helpers.
  local pkg_name
  for pkg_name in libarchive tar curl python; do
    if dpkg -s "$pkg_name" >/dev/null 2>&1; then
      continue
    fi
    say "installing $pkg_name..."
    pkg install -y "$pkg_name" >/dev/null 2>&1 || warn "could not install '$pkg_name'"
  done
  dpkg -s libarchive >/dev/null 2>&1 || die "libarchive is required by the server binary"
  have python3 || die "python3 is required by the helper scripts"
  ok "runtime packages present"
}

ensure_build_toolchain() {
  step "Checking build toolchain"

  if ! have rustc; then
    say "Installing Rust 1.99.1 + wasm target (large download, ~160 MB)..."
    pkg install -y rust rust-std-wasm32-unknown-unknown 2>&1 | tail -3
  fi
  have rustc || die "rustc is required to build"
  ok "rustc $(rustc --version | awk '{print $2}')"

  if ! have trunk; then
    say "Installing trunk..."
    pkg install -y trunk 2>&1 | tail -3
  fi
  have trunk || die "trunk is required to build the web UI"
  ok "trunk $(trunk --version 2>/dev/null | awk '{print $2}')"

  if ! have wasm-opt; then
    say "Installing binaryen (wasm-opt)..."
    pkg install -y binaryen 2>&1 | tail -3
  fi
  have wasm-opt || die "wasm-opt is required"
  ok "wasm-opt $(wasm-opt --version 2>/dev/null | awk '{print $3}')"

  if ! have wasm-bindgen; then
    say "Building wasm-bindgen-cli ${WASM_BINDGEN_VERSION} (takes several minutes)..."
    cargo install wasm-bindgen-cli --version "$WASM_BINDGEN_VERSION" --locked 2>&1 | tail -3
  fi
  have wasm-bindgen || die "wasm-bindgen-cli is required"
  ok "wasm-bindgen $(wasm-bindgen --version | awk '{print $2}')"

  if ! have sass; then
    say "Installing dart-sass ${SASS_VERSION}..."
    local work="$HOME/.tanoshi-build"
    mkdir -p "$work" "$HOME/.local/bin"
    ( cd "$work" && npm install "sass@${SASS_VERSION}" 2>&1 | tail -2 )
    cat >"$HOME/.local/bin/sass" <<EOF
#!/data/data/com.termux/files/usr/bin/sh
exec ${PREFIX}/bin/node "$work/node_modules/sass/sass.js" "\$@"
EOF
    chmod +x "$HOME/.local/bin/sass"
    export PATH="$HOME/.local/bin:$PATH"
  fi
  have sass || die "dart-sass is required"
  ok "sass $(sass --version 2>/dev/null | head -1)"

  # wasm-bindgen/sass live in these two dirs.
  export PATH="$HOME/.cargo/bin:$HOME/.local/bin:$PATH"
}
