#!/usr/bin/env bash
#
# Refresh android/app/src/main/jniLibs/arm64-v8a from the built artefacts.
#
# The binaries checked into this repository were produced with:
#   * libtanoshi.so   — build_tanoshi.sh (patched Tanoshi v0.40.0 for Android)
#   * libarchive.so   — build_libarchive.sh (minimal libarchive 3.8.11)
#   * lib<name>.so    — the nine extension plugins shipped in the Termux package
#                       (`tanoshi-termux-aarch64.tar.gz` in the repository root)
#
# Usage:
#   ./sync_prebuilt.sh
#   ./sync_prebuilt.sh --tanoshi ~/.tanoshi-build/native-target/release/tanoshi \
#                      --libarchive native/out/libarchive.so \
#                      --plugins ../tanoshi/plugins
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
JNI_LIBS_DIR="${JNI_LIBS_DIR:-$REPO_ROOT/android/app/src/main/jniLibs/arm64-v8a}"
PLUGIN_PACKAGE="${PLUGIN_PACKAGE:-$REPO_ROOT/tanoshi-termux-aarch64.tar.gz}"

TANOSHI_BIN="${TANOSHI_BIN:-$HOME/.tanoshi-build/native-target/release/tanoshi}"
LIBARCHIVE_SO="${LIBARCHIVE_SO:-$HERE/out/libarchive.so}"
PLUGINS_DIR="${PLUGINS_DIR:-}"

die() { printf '\nerror: %s\n' "$*" >&2; exit 1; }
say() { printf '==> %s\n' "$*"; }

while [ $# -gt 0 ]; do
    case "$1" in
        --tanoshi) TANOSHI_BIN="${2:?--tanoshi needs a path}"; shift 2 ;;
        --libarchive) LIBARCHIVE_SO="${2:?--libarchive needs a path}"; shift 2 ;;
        --plugins) PLUGINS_DIR="${2:?--plugins needs a directory}"; shift 2 ;;
        -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "unknown option: $1" ;;
    esac
done

[ -f "$TANOSHI_BIN" ] || die "server binary not found: $TANOSHI_BIN (run build_tanoshi.sh or pass --tanoshi)"
[ -f "$LIBARCHIVE_SO" ] || die "libarchive not found: $LIBARCHIVE_SO (run build_libarchive.sh or pass --libarchive)"

mkdir -p "$JNI_LIBS_DIR"
cp "$TANOSHI_BIN" "$JNI_LIBS_DIR/libtanoshi.so"
cp "$LIBARCHIVE_SO" "$JNI_LIBS_DIR/libarchive.so"
say "copied libtanoshi.so and libarchive.so"

# ---------------------------------------------------------------------------
# Plugins: the Termux package ships plugins/<name>.so. Android's build tools
# only extract jniLibs files matching lib*.so, so rename them to lib<name>.so;
# the patched Tanoshi server loads every *.so from nativeLibraryDir and derives
# the extension name from the file name.
# ---------------------------------------------------------------------------
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/tanoshi-plugins.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

if [ -n "$PLUGINS_DIR" ]; then
    [ -d "$PLUGINS_DIR" ] || die "plugins directory not found: $PLUGINS_DIR"
    cp "$PLUGINS_DIR"/*.so "$TMP_DIR"/
else
    [ -f "$PLUGIN_PACKAGE" ] || die "plugin package not found: $PLUGIN_PACKAGE (pass --plugins)"
    tar -xzf "$PLUGIN_PACKAGE" -C "$TMP_DIR" --strip-components=2 tanoshi/plugins/ \
        || die "could not extract plugins from $PLUGIN_PACKAGE"
fi

count=0
for plugin in "$TMP_DIR"/*.so; do
    [ -f "$plugin" ] || continue
    name="$(basename "$plugin" .so)"
    cp "$plugin" "$JNI_LIBS_DIR/lib${name}.so"
    count=$((count + 1))
done
[ "$count" -gt 0 ] || die "no plugins found to copy"

chmod 644 "$JNI_LIBS_DIR"/*.so
say "copied $count extension plugins into $JNI_LIBS_DIR"
ls -la "$JNI_LIBS_DIR"
