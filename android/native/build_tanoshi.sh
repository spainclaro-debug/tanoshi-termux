#!/usr/bin/env bash
#
# Build the Tanoshi server (`libtanoshi.so`) for Android arm64.
#
# Pipeline:
#   1. clone upstream luigi311/tanoshi at the pinned tag
#   2. apply the Termux/Android patch set that lives in this repo (`patches/`)
#   3. apply `android/native/patches/0001-android-app.patch` (bind address,
#      read-only plugin loading, disabled in-app extension installs)
#   4. pin the patched compress-tools crate (vendored in this repo) via Cargo
#   5. build the wasm web UI (reuse android/native/web-dist when trunk is absent)
#   6. cargo build --release, then copy the binary to
#      android/app/src/main/jniLibs/arm64-v8a/libtanoshi.so
#
# Two toolchains are supported:
#   * Termux on the device: the installed rustc already targets
#     aarch64-linux-android (this is how the checked-in binary was produced)
#   * a desktop host with an Android NDK: install the Rust target
#     aarch64-linux-android and point ANDROID_NDK_HOME at the NDK.
#
# Usage:
#   ./build_tanoshi.sh
#   ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/26.1.10909125 ./build_tanoshi.sh
#
# Environment:
#   TANOSHI_REF        upstream git tag/branch (default: v0.40.0)
#   ANDROID_NDK_HOME   Android NDK (desktop build)
#   LIBARCHIVE_OUT     directory containing libarchive.pc (default: native/out,
#                      produced by build_libarchive.sh)
#   WORK_DIR           source/build directory (default: native/.work)
#   SKIP_WEB           set to 1 to require a pre-built crates/tanoshi-web/dist
#   JNI_LIBS_DIR       output directory (default: app/src/main/jniLibs/arm64-v8a)
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
JNI_LIBS_DIR="${JNI_LIBS_DIR:-$REPO_ROOT/android/app/src/main/jniLibs/arm64-v8a}"
WORK_DIR="${WORK_DIR:-$HERE/.work}"
SRC_DIR="${SRC_DIR:-$WORK_DIR/tanoshi-src}"
CARGO_HOME_DIR="${CARGO_HOME_DIR:-$WORK_DIR/cargo-home}"
TARGET_DIR="${TARGET_DIR:-$WORK_DIR/target}"
LIBARCHIVE_OUT="${LIBARCHIVE_OUT:-$HERE/out}"
TANOSHI_REF="${TANOSHI_REF:-v0.40.0}"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 4)}"
SKIP_WEB="${SKIP_WEB:-0}"

die() { printf '\nerror: %s\n' "$*" >&2; exit 1; }
say() { printf '==> %s\n' "$*"; }

command -v cargo >/dev/null 2>&1 || die "cargo not found (install the Rust toolchain)"
command -v git >/dev/null 2>&1 || die "git not found"

IN_TERMUX=0
if [ -n "${PREFIX:-}" ] && [ -d /data/data/com.termux/files/usr ]; then IN_TERMUX=1; fi

# ---------------------------------------------------------------------------
# 1. Source checkout
# ---------------------------------------------------------------------------
if [ ! -d "$SRC_DIR/.git" ]; then
    say "cloning luigi311/tanoshi ($TANOSHI_REF)"
    rm -rf "$SRC_DIR"
    git clone --depth 1 --branch "$TANOSHI_REF" https://github.com/luigi311/tanoshi.git "$SRC_DIR" \
        || die "failed to clone upstream; check the tag and network"
else
    say "reusing source checkout at $SRC_DIR"
fi
cd "$SRC_DIR"
git checkout --force --quiet -- . 2>/dev/null || true
git clean -fdq 2>/dev/null || true

# ---------------------------------------------------------------------------
# 2. Patches
# ---------------------------------------------------------------------------
say "applying Termux/Android patches from $REPO_ROOT/patches"
apply_patch() {
    local patch="$1"
    if git apply --check --whitespace=nowarn "$patch" 2>/dev/null; then
        git apply --whitespace=nowarn "$patch"
    elif git apply --3way --whitespace=nowarn "$patch" 2>/dev/null; then
        :
    else
        die "cannot apply $patch (upstream changed?); review the patch set"
    fi
}
apply_patch "$REPO_ROOT/patches/tanoshi-android.patch"
cp "$REPO_ROOT/patches/http.rs.new" crates/tanoshi/src/infrastructure/http.rs
cp "$REPO_ROOT/patches/extension-build.rs.new" crates/tanoshi-vm/src/extension/build.rs
if [ -f "$REPO_ROOT/patches/tanoshi-infinite-scroll.patch" ]; then
    apply_patch "$REPO_ROOT/patches/tanoshi-infinite-scroll.patch" || true
fi
say "applying Android app patch"
apply_patch "$HERE/patches/0001-android-app.patch"

# compress-tools is patched for Android/bionic and linked dynamically against
# libarchive (see patches/README-patches.txt in the repo root).
mkdir -p "$CARGO_HOME_DIR"
cat >"$CARGO_HOME_DIR/config.toml" <<EOF
[patch.crates-io]
compress-tools = { path = "$REPO_ROOT/vendor/compress-tools-0.16.1" }
EOF
export CARGO_HOME="$CARGO_HOME_DIR"

# ---------------------------------------------------------------------------
# 3. Web UI (embedded into the server binary with rust-embed)
# ---------------------------------------------------------------------------
if [ -f crates/tanoshi-web/dist/index.html ]; then
    say "web UI dist already present"
elif [ "$SKIP_WEB" = "1" ]; then
    die "crates/tanoshi-web/dist is missing and SKIP_WEB=1"
elif [ -f "$HERE/web-dist/index.html" ]; then
    say "copying vendored web UI from android/native/web-dist"
    rm -rf crates/tanoshi-web/dist
    cp -R "$HERE/web-dist" crates/tanoshi-web/dist
elif command -v trunk >/dev/null 2>&1; then
    say "building web UI with trunk"
    ( cd crates/tanoshi-web && trunk build --release )
else
    die "no web UI dist and no trunk; install trunk + wasm-bindgen + sass or pass SKIP_WEB=1 with a prepared dist"
fi
[ -f crates/tanoshi-web/dist/index.html ] || die "web UI build produced no dist/index.html"

# ---------------------------------------------------------------------------
# 4. Native server build
# ---------------------------------------------------------------------------
RUSTFLAGS_COMMON="-Clink-arg=-Wl,--allow-multiple-definition -Clink-arg=-Wl,-z,max-page-size=16384"
export RUSTFLAGS="$RUSTFLAGS_COMMON${RUSTFLAGS:+ $RUSTFLAGS}"
export CARGO_TARGET_DIR="$TARGET_DIR"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -n "$NDK" ]; then
    [ -d "$NDK/toolchains/llvm/prebuilt" ] || die "ANDROID_NDK_HOME=$NDK is not an NDK"
    [ -f "$LIBARCHIVE_OUT/pkgconfig/libarchive.pc" ] \
        || die "missing $LIBARCHIVE_OUT/pkgconfig/libarchive.pc; run build_libarchive.sh first"
    case "$(uname -s)" in
        Darwin) HOST_TAG="darwin-x86_64" ;;
        *) HOST_TAG="linux-x86_64" ;;
    esac
    TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
    export CC_aarch64_linux_android="$TOOLCHAIN/bin/aarch64-linux-android26-clang"
    export AR_aarch64_linux_android="$TOOLCHAIN/bin/llvm-ar"
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC_aarch64_linux_android"
    export PKG_CONFIG_PATH="$LIBARCHIVE_OUT/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
    export PKG_CONFIG_ALLOW_CROSS=1
    export RUSTFLAGS="$RUSTFLAGS -Clink-arg=-L$LIBARCHIVE_OUT"
    say "building Tanoshi for aarch64-linux-android with NDK"
    rustup target add aarch64-linux-android || die "could not install the aarch64-linux-android Rust target"
    cargo build -p tanoshi --release --target aarch64-linux-android -j "$JOBS"
    BUILT_BIN="$TARGET_DIR/aarch64-linux-android/release/tanoshi"
elif [ "$IN_TERMUX" = "1" ]; then
    # Termux's rustc is a native aarch64-linux-android toolchain.
    say "building Tanoshi with the Termux toolchain"
    cargo build -p tanoshi --release -j "$JOBS"
    BUILT_BIN="$TARGET_DIR/release/tanoshi"
else
    die "no Android toolchain: run inside Termux or set ANDROID_NDK_HOME"
fi

[ -x "$BUILT_BIN" ] || die "build produced no binary at $BUILT_BIN"
mkdir -p "$JNI_LIBS_DIR"
cp "$BUILT_BIN" "$JNI_LIBS_DIR/libtanoshi.so"
chmod 644 "$JNI_LIBS_DIR/libtanoshi.so"

say "installed $JNI_LIBS_DIR/libtanoshi.so"
file "$JNI_LIBS_DIR/libtanoshi.so" 2>/dev/null || true
cat <<EOF

done.
Verify with:
  readelf -d android/app/src/main/jniLibs/arm64-v8a/libtanoshi.so | grep NEEDED
  readelf -l android/app/src/main/jniLibs/arm64-v8a/libtanoshi.so | grep LOAD

Expected: NEEDED contains libarchive.so / libdl.so / libm.so / libc.so and every
LOAD segment is aligned to 0x4000 (16 KB).
EOF
