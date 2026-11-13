#!/usr/bin/env bash
#
# Build a minimal `libarchive.so` for Android arm64 (aarch64-linux-android).
#
# The Tanoshi server binary links against libarchive dynamically (see
# android/native/patches/0001-android-app.patch and the patched compress-tools
# crate in the repo root). Android does not ship libarchive, so the app bundles
# this library into jniLibs/arm64-v8a/libarchive.so.
#
# The build is intentionally minimal: only ZIP/deflate support (zlib is linked
# statically), no OpenSSL/libxml2/iconv/bzip2/xz/zstd/lz4 dependencies. The
# resulting shared object therefore only needs bionic libc and libdl, and is
# safe to load inside an Android app on API 26+.
#
# Usage:
#   ./build_libarchive.sh                 # auto-detects Termux or ANDROID_NDK_HOME
#   ANDROID_NDK_HOME=/path/to/ndk ./build_libarchive.sh
#   OUT_DIR=/path/to/out ./build_libarchive.sh
#
# Environment:
#   ANDROID_NDK_HOME / ANDROID_NDK_ROOT  Android NDK (r26+ recommended)
#   ANDROID_API                          target API level (default 26)
#   OUT_DIR                              output directory (default: native/out)
#   WORK_DIR                             download/build directory (default: native/.work)
#   JOBS                                 parallel build jobs (default: nproc)
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${OUT_DIR:-$HERE/out}"
WORK_DIR="${WORK_DIR:-$HERE/.work}"
ANDROID_API="${ANDROID_API:-26}"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 4)}"

LIBARCHIVE_VERSION="3.8.11"
LIBARCHIVE_URL="https://github.com/libarchive/libarchive/releases/download/v${LIBARCHIVE_VERSION}/libarchive-${LIBARCHIVE_VERSION}.tar.gz"
LIBARCHIVE_SHA256="900fe9fc7237c2b04f090f3c3648de8fe597525771f5035016537ce2d667d12b"

ZLIB_VERSION="1.3.1"
ZLIB_URL="https://github.com/madler/zlib/releases/download/v${ZLIB_VERSION}/zlib-${ZLIB_VERSION}.tar.gz"
ZLIB_SHA256="9a93b2b7dfdac77ceba5a558a580e74667dd6fede4585b91eefb60f03b72df23"

die() { printf '\nerror: %s\n' "$*" >&2; exit 1; }
say() { printf '==> %s\n' "$*"; }

mkdir -p "$OUT_DIR" "$WORK_DIR"
cd "$WORK_DIR"

fetch() {
    local url="$1" sha="$2" file="$3"
    if [ -f "$file" ] && echo "$sha  $file" | sha256sum -c - >/dev/null 2>&1; then
        say "using cached $file"
        return 0
    fi
    say "downloading $file"
    curl -fsSL -o "$file.part" "$url"
    echo "$sha  $file.part" | sha256sum -c - >/dev/null 2>&1 \
        || die "sha256 mismatch for $file"
    mv "$file.part" "$file"
}

fetch "$LIBARCHIVE_URL" "$LIBARCHIVE_SHA256" "libarchive-${LIBARCHIVE_VERSION}.tar.gz"
fetch "$ZLIB_URL" "$ZLIB_SHA256" "zlib-${ZLIB_VERSION}.tar.gz"
[ -d "libarchive-${LIBARCHIVE_VERSION}" ] || tar -xzf "libarchive-${LIBARCHIVE_VERSION}.tar.gz"
[ -d "zlib-${ZLIB_VERSION}" ] || tar -xzf "zlib-${ZLIB_VERSION}.tar.gz"

# ---------------------------------------------------------------------------
# Pick a compiler: the Android NDK when available, otherwise the Termux
# toolchain (Termux's clang already targets aarch64-linux-android).
# ---------------------------------------------------------------------------
CMAKE_TOOLCHAIN_ARGS=()
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -n "$NDK" ]; then
    [ -d "$NDK/toolchains/llvm/prebuilt" ] || die "ANDROID_NDK_HOME=$NDK does not look like an NDK"
    case "$(uname -s)" in
        Darwin) HOST_TAG="darwin-x86_64" ;;
        *) HOST_TAG="linux-x86_64" ;;
    esac
    TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
    CC="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang"
    AR="$TOOLCHAIN/bin/llvm-ar"
    STRIP="$TOOLCHAIN/bin/llvm-strip"
    [ -x "$CC" ] || die "missing NDK compiler: $CC"
    CMAKE_TOOLCHAIN_ARGS=(
        "-DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake"
        "-DANDROID_ABI=arm64-v8a"
        "-DANDROID_PLATFORM=android-${ANDROID_API}"
    )
    say "toolchain: Android NDK ($CC)"
elif command -v clang >/dev/null 2>&1 && [ -n "${PREFIX:-}" ] && [ "$(uname -m)" = "aarch64" ]; then
    CC="clang"
    AR="ar"
    STRIP="strip"
    say "toolchain: Termux clang ($(clang --version | head -1))"
else
    die "no Android toolchain found; set ANDROID_NDK_HOME or run inside Termux"
fi

# ---------------------------------------------------------------------------
# zlib (static, PIC) — linked into libarchive.so so the APK needs no libz.so.
# ---------------------------------------------------------------------------
say "building zlib ${ZLIB_VERSION} (static)"
mkdir -p build/zlib-obj
(
    cd "zlib-${ZLIB_VERSION}"
    for src in adler32.c compress.c crc32.c deflate.c gzclose.c gzlib.c gzread.c \
               gzwrite.c infback.c inffast.c inflate.c inftrees.c trees.c uncompr.c zutil.c; do
        "$CC" -O2 -fPIC -DHAVE_UNISTD_H -I. -c "$src" -o "../build/zlib-obj/${src%.c}.o"
    done
)
"$AR" rcs build/zlib-obj/libz.a build/zlib-obj/*.o

# ---------------------------------------------------------------------------
# libarchive (shared, minimal feature set)
# ---------------------------------------------------------------------------
say "configuring libarchive ${LIBARCHIVE_VERSION}"
rm -rf build/libarchive
cmake -S "libarchive-${LIBARCHIVE_VERSION}" -B build/libarchive \
    "${CMAKE_TOOLCHAIN_ARGS[@]}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_COMPILER="$CC" \
    -DCMAKE_C_FLAGS="-O2 -fPIC" \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
    -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
    -DBUILD_SHARED_LIBS=ON \
    -DENABLE_ZLIB=ON \
    -DZLIB_LIBRARY="$WORK_DIR/build/zlib-obj/libz.a" \
    -DZLIB_INCLUDE_DIR="$WORK_DIR/zlib-${ZLIB_VERSION}" \
    -DENABLE_BZip2=OFF -DENABLE_LZMA=OFF -DENABLE_ZSTD=OFF -DENABLE_LZ4=OFF \
    -DENABLE_LZO=OFF -DENABLE_LIBXML2=OFF -DENABLE_BSDXML=OFF -DENABLE_EXPAT=OFF \
    -DENABLE_PCREPOSIX=OFF -DENABLE_PCRE2POSIX=OFF -DENABLE_OPENSSL=OFF \
    -DENABLE_MBEDTLS=OFF -DENABLE_NETTLE=OFF -DENABLE_MD=OFF -DENABLE_LIBB2=OFF \
    -DENABLE_ICONV=OFF -DENABLE_XATTR=OFF -DENABLE_ACL=OFF -DENABLE_TEST=OFF \
    -DENABLE_TAR=OFF -DENABLE_CPIO=OFF -DENABLE_CAT=OFF -DENABLE_UNZIP=OFF \
    -DENABLE_INSTALL=OFF -DENABLE_WERROR=OFF >/dev/null

# Android bionic does not expose lchmod()/close_range() the way libarchive's
# configure probes expect (the probes succeed against the Termux sysroot but the
# declarations are missing), so force the fallback code paths.
python3 - "$WORK_DIR/build/libarchive/config.h" <<'PY'
import re
import sys

path = sys.argv[1]
text = open(path).read()
for symbol in ("HAVE_LCHMOD", "HAVE_CLOSE_RANGE"):
    text = re.sub(r"^#define %s 1$" % symbol, "/* #undef %s */" % symbol, text, flags=re.M)
open(path, "w").write(text)
print("patched config.h: lchmod/close_range disabled")
PY

say "building libarchive (jobs=$JOBS)"
cmake --build build/libarchive --target archive -j "$JOBS" >/dev/null

BUILT_SO="$(find build/libarchive -name 'libarchive.so' -type f | head -1)"
[ -n "$BUILT_SO" ] || die "libarchive build produced no libarchive.so"

cp "$BUILT_SO" "$OUT_DIR/libarchive.so"
"$STRIP" --strip-unneeded "$OUT_DIR/libarchive.so" 2>/dev/null || true

# The Tanoshi binary's DT_NEEDED entry is exactly "libarchive.so"; make the
# SONAME match so the Android linker is happy.
python3 - "$OUT_DIR/libarchive.so" <<'PY'
import struct
import sys

path = sys.argv[1]
data = bytearray(open(path, "rb").read())
if data[:4] != b"\x7fELF" or data[4] != 2:
    raise SystemExit("not a 64-bit ELF: " + path)

shoff = struct.unpack_from("<Q", data, 0x28)[0]
shentsize = struct.unpack_from("<H", data, 0x3A)[0]
shnum = struct.unpack_from("<H", data, 0x3C)[0]
shstrndx = struct.unpack_from("<H", data, 0x3E)[0]
sections = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + i * shentsize) for i in range(shnum)]

def section_name(i):
    start = sections[shstrndx][4] + sections[i][0]
    return data[start:data.index(b"\0", start)].decode()

def section(name):
    for i, sec in enumerate(sections):
        if section_name(i) == name:
            return sec
    raise SystemExit("missing section " + name)

dynamic = section(".dynamic")
dynstr = sections[dynamic[6]]
new_soname = b"libarchive.so"
for offset in range(0, dynamic[5], 16):
    tag, value = struct.unpack_from("<qQ", data, dynamic[4] + offset)
    if tag == 0:
        break
    if tag == 14:  # DT_SONAME
        start = dynstr[4] + value
        end = data.index(b"\0", start)
        data[start:start + len(new_soname)] = new_soname
        for i in range(start + len(new_soname), end):
            data[i] = 0
        break
open(path, "wb").write(data)
print("patched SONAME -> libarchive.so")
PY

# pkg-config file consumed by compress-tools when building the Tanoshi server.
mkdir -p "$OUT_DIR/pkgconfig"
cat >"$OUT_DIR/pkgconfig/libarchive.pc" <<EOF
prefix=$OUT_DIR
libdir=\${prefix}
includedir=\${prefix}/include

Name: libarchive
Description: library that can create and read several streaming archive formats (minimal Android build)
Version: ${LIBARCHIVE_VERSION}
Libs: -L\${libdir} -larchive
Cflags:
EOF

cat <<EOF

done: $OUT_DIR/libarchive.so ($(du -h "$OUT_DIR/libarchive.so" | cut -f1))
      $OUT_DIR/pkgconfig/libarchive.pc
next: bash android/native/sync_prebuilt.sh   # or build_tanoshi.sh
EOF
