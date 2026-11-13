# Native binaries for the Tanoshi Android app

The Android app in `android/` does not compile any Rust code. It ships the
Tanoshi server, its `libarchive` dependency and the extension plugins as
prebuilt `arm64-v8a` shared objects inside the APK, and only runs Gradle.

This directory contains the pipeline that produces those binaries. The
checked-in files under `android/app/src/main/jniLibs/arm64-v8a/` were built
with exactly these scripts; re-run them to update Tanoshi or a plugin.

The same upstream Tanoshi source and the same Termux patch set used by the
root package (`patches/`, `vendor/compress-tools-0.16.1/`, `lib/build.sh`)
are reused here, with one extra patch that adapts the server to the Android
app runtime.

---

## What gets built

| Output in `jniLibs/arm64-v8a/` | Built from | Notes |
| --- | --- | --- |
| `libtanoshi.so` | `luigi311/tanoshi` v0.40.0, patched with this repo's Termux patch set + `native/patches/0001-android-app.patch` | The server binary, **renamed** from `tanoshi` so the package installer extracts it to `nativeLibraryDir` (the only executable location on Android 10+). ~35 MB. |
| `libarchive.so` | [libarchive](https://github.com/libarchive/libarchive) 3.8.11 | Minimal shared build: zlib linked in **statically** (no `libz.so` runtime dependency), no OpenSSL, libxml2 or iconv; only libc/libdl remain as `NEEDED` entries; SONAME patched to plain `libarchive.so`. Loaded by `libtanoshi.so` through `LD_LIBRARY_PATH`. |
| `lib<plugin>.so` | [luigi311/tanoshi-extensions](https://github.com/luigi311/tanoshi-extensions) + this repo's ported extensions | Nine plugins: `libmangadex.so`, `libguya.so`, `libmanhwa18cc.so`, `libweebcentral.so`, `libatsumaru.so`, `libqiscans.so`, `libmangaball.so`, `libmadaradex.so`, `libhentainexus.so`. Renamed with the `lib` prefix for the same reason as the server. |

All of these files must be ELF aarch64 shared objects. Android only loads or
executes them from `nativeLibraryDir`; nothing under `filesDir` is executable.

`libtanoshi.so` also embeds the Tanoshi web UI: the wasm bundle from
`android/native/web-dist/` (the package-3 dist built with
`patches/tanoshi-infinite-scroll.patch`) is compiled into the server binary,
so the catalogue/infinite-scroll behaviour ships with the server rather than
with the Android app.

---

## Prerequisites

A Linux x86_64 build host (the checked-in binaries were produced on Linux
with the Android NDK). The phone needs no Rust toolchain and does not build
these binaries.

- **Android NDK r26 or newer**, with `ANDROID_NDK_HOME` pointing at it.
  r26d / r27 are known-good.
- **Rust 1.99.1** (the version the Termux package pins), installed with
  `rustup`, plus the cross targets:

  ```bash
  rustup target add aarch64-linux-android wasm32-unknown-unknown
  ```

- **Web UI tooling** (only needed when rebuilding the embedded web UI):
  `trunk`, `wasm-bindgen-cli` **0.2.129**, `sass` **1.106.1** and
  `wasm-opt` (binaryen) **133** — these versions are pinned by the upstream
  Tanoshi web workspace and are the same ones the Termux scripts use
  (`lib/common.sh`).
- **CMake 3.22+**, `make`, `pkg-config`, `git`, `curl`, `python3`.
- About **10 GB** of free disk space for source checkouts and Cargo targets.

`build_tanoshi.sh` checks the toolchain and prints what is missing.

---

## Environment for cross-compilation

Export this in every shell that builds native code. `API` is the app's
`minSdk` (26).

```bash
export ANDROID_NDK_HOME="$HOME/android-ndk-r26d"          # adjust
export TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
export API=26
export PATH="$TOOLCHAIN/bin:$PATH"

export CC_aarch64_linux_android="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
export AR_aarch64_linux_android="$TOOLCHAIN/bin/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"

# The Termux build needs --allow-multiple-definition; 16 KB page alignment is
# required for Android 15+ devices with 16 KB pages (see "Verifying" below).
export RUSTFLAGS="-Clink-arg=-Wl,--allow-multiple-definition -Clink-arg=-Wl,-z,max-page-size=16384"
```

The `CC_*` and `AR_*` names are written with underscores because a shell
cannot export a variable whose name contains `-`; the `cc` crate accepts
either form.

---

## Pipeline

The scripts to run are:

| Script | Purpose |
| --- | --- |
| `native/build_tanoshi.sh` | Fetch upstream Tanoshi at the pinned version, apply this repo's Termux patch set and the Android app patch, embed the vendored `native/web-dist/` web UI (package-3, infinite scroll) when the source tree has no `dist/`, and build the `aarch64-linux-android` server binary. |
| `native/build_libarchive.sh` | Cross-build the minimal `libarchive.so` (zlib only) with the NDK CMake toolchain and stage its `pkg-config` file for `compress-tools`. |
| `native/sync_prebuilt.sh` | Copy the built `tanoshi`, `libarchive.so` and the nine plugins into `android/app/src/main/jniLibs/arm64-v8a/` with the `lib` prefix and validate the ELF files. |

The scripts are self-checking. Typical order: run `native/build_libarchive.sh`
first (its staging prefix is what `compress-tools` links against), then
`native/build_tanoshi.sh`, then `native/sync_prebuilt.sh` from the `android/`
directory. Each script documents its own options (`--help`);
`sync_prebuilt.sh` does not rebuild anything. The subsections below describe
the equivalent manual steps, which is what the scripts automate.

### 1. Tanoshi source and patches

`build_tanoshi.sh` clones upstream and applies, in order:

1. The **Termux patch set** from this repository's `patches/` directory
   (`tanoshi-android.patch` plus the two new files `http.rs.new` and
   `extension-build.rs.new` — see `patches/README-patches.txt`). This makes
   the server compile and run with Bionic and private CA roots.
2. **`native/patches/0001-android-app.patch`**, the Android-app-only layer:
   - the listen address is read from the `TANOSHI_BIND` environment variable
     instead of the hardcoded `0.0.0.0` (defaults to `127.0.0.1`);
   - plugins are loaded in place from `nativeLibraryDir` instead of being
     staged/copied into the writable plugin directory, because Android 10+
     refuses to `dlopen` code from app-writable storage;
   - the writable plugin directory is still passed to the plugins as their
     preferences directory.
3. Optionally `patches/tanoshi-infinite-scroll.patch` (the Termux package's
   auto-loading catalogue). This only matters when regenerating the wasm web
   UI: the vendored `native/web-dist/` already contains the patched output, so
   a normal server rebuild uses it as-is.

Manually:

```bash
git clone https://github.com/luigi311/tanoshi.git
cd tanoshi
git checkout v0.40.0

# Termux patch set from the repository root
git apply ../patches/tanoshi-android.patch
cp ../patches/http.rs.new      crates/tanoshi/src/infrastructure/http.rs
cp ../patches/extension-build.rs.new crates/tanoshi-vm/src/extension/build.rs
git apply ../patches/tanoshi-infinite-scroll.patch   # optional

# Android app layer
git apply ../android/native/patches/0001-android-app.patch

# Use the vendored, Android-patched compress-tools crate
export CARGO_HOME="$PWD/cargo-home"
mkdir -p "$CARGO_HOME"
cat > "$CARGO_HOME/config.toml" <<EOF
[patch.crates-io]
compress-tools = { path = "$(cd .. && pwd)/vendor/compress-tools-0.16.1" }
EOF
```

### 2. Minimal `libarchive.so`

The vendored `compress-tools` probes `libarchive` through `pkg-config`, so
libarchive must be installed into a staging prefix first. The build is
deliberately minimal — Tanoshi only needs ZIP/gzip handling for chapter
archives — and zlib is linked into the library statically so the APK does not
need a separate `libz.so`.

```bash
export PREFIX="$PWD/android-deps/arm64-v8a"
cmake -S libarchive -B build-libarchive \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM="android-$API" \
  -DCMAKE_INSTALL_PREFIX="$PREFIX" \
  -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
  -DBUILD_SHARED_LIBS=ON \
  -DENABLE_ZLIB=ON \
  -DENABLE_OPENSSL=OFF -DENABLE_LIBXML2=OFF -DENABLE_EXPAT=OFF -DENABLE_ICONV=OFF \
  -DENABLE_BZip2=OFF -DENABLE_LZMA=OFF -DENABLE_LZ4=OFF -DENABLE_ZSTD=OFF \
  -DENABLE_TEST=OFF -DENABLE_TAR=OFF -DENABLE_CPIO=OFF -DENABLE_CAT=OFF
cmake --build build-libarchive -j"$(nproc)"
cmake --install build-libarchive
```

The block above is the baseline configure; `build_libarchive.sh` is the
authoritative invocation. It pins **libarchive 3.8.11**, makes zlib a static
input of `libarchive.so`, and patches the SONAME to plain `libarchive.so` (a
stock build would name it `libarchive.so.<major>`, which the runtime lookup
does not expect). Any libarchive release **≥ 3.2.0** satisfies
`compress-tools`, but keep the pinned version for reproducible output. Then point `pkg-config` at the staged files
and tell Cargo where to link:

```bash
export PKG_CONFIG_ALLOW_CROSS=1
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
# The .pc file contains absolute paths, so no PKG_CONFIG_SYSROOT_DIR is needed.
```

### 3. Web UI and server binary

The server binary embeds the Tanoshi web UI (`crates/tanoshi-web/dist`) with
`rust-embed`, so a web UI dist must exist before the server is compiled. The
default source is the **vendored dist** in this directory:

- `android/native/web-dist/` is the **package-3 web UI** — the same dist the
  Termux package-3 archive uses: upstream Tanoshi 0.40.0's wasm UI built with
  `patches/tanoshi-infinite-scroll.patch` applied. Its wasm bundle contains
  the `catalogue-scroll-sentinel`, which auto-loads the next catalogue page
  about 600 px before the viewport and turns the **Load More** button into
  *No more results* at the end.
- `build_tanoshi.sh` picks the web UI in this order: an existing
  `crates/tanoshi-web/dist/`, then the vendored `android/native/web-dist/`
  (copied into place), then `trunk build --release`. A plain
  `./build_tanoshi.sh` therefore uses the infinite-scroll UI with no web
  toolchain installed.
- The checked-in `libtanoshi.so` was rebuilt this way for app **v0.2.0**.

To change the web UI itself, rebuild the wasm dist with trunk (using the
pinned tool versions from the prerequisites) and refresh the vendored copy so
future native builds stay consistent:

```bash
cd crates/tanoshi-web
CARGO_TARGET_DIR="$PWD/../../target-wasm" trunk build --release
cd ../..
rm -rf android/native/web-dist && cp -R crates/tanoshi-web/dist android/native/web-dist
```

Then build the server. Make sure the `pkg-config` environment from step 2
(`PKG_CONFIG_ALLOW_CROSS`, `PKG_CONFIG_PATH`) is still exported, because the
vendored `compress-tools` build script probes libarchive at compile time:

```bash
CARGO_TARGET_DIR="$PWD/target-android" \
  cargo build -p tanoshi --release --target aarch64-linux-android
```

Result: `target-android/aarch64-linux-android/release/tanoshi`.

### 4. Extension plugins

The nine plugins are the same native libraries the Termux package ships. They
are already built for `aarch64-linux-android` against the `tanoshi-lib`
version the server uses (0.38.0 for Tanoshi 0.40.0); the Termux package names
them `plugins/<name>.so`, and `sync_prebuilt.sh` renames them to
`lib<name>.so` for `nativeLibraryDir`. If a plugin must be rebuilt from
source, use the upstream extension workspace with the Termux networking
helpers/icon fixes applied, plus the five extensions ported in this
repository's `extensions/` directory:

```bash
git clone https://github.com/luigi311/tanoshi-extensions.git extensions-src
cd extensions-src
git apply ../patches/networking-helpers.patch
git apply ../patches/extension-icons.patch
cp -r ../extensions/* ./extensions/

CARGO_TARGET_DIR="$PWD/../target-extensions" \
  cargo build --release --target aarch64-linux-android \
  -p mangadex -p guya -p manhwa18cc -p weebcentral \
  -p atsumaru -p qiscans -p mangaball -p madaradex -p hentainexus
```

Each plugin ends up as `lib<name>.so`. If upstream changed the files these
patches touch, `git apply` fails; see `patches/README-patches.txt` for the
current patch contents. Never mix plugins built against a different
`tanoshi-lib` version — they refuse to load.

### 5. Copy into the app

`native/sync_prebuilt.sh` performs the rename and the copy:

```bash
cd android
native/sync_prebuilt.sh
```

which is equivalent to:

```bash
DEST=app/src/main/jniLibs/arm64-v8a
cp target-android/aarch64-linux-android/release/tanoshi "$DEST/libtanoshi.so"
cp "$PREFIX/lib/libarchive.so"                              "$DEST/libarchive.so"

# Plugins: either from the Termux package (already aarch64 Android builds)...
for name in mangadex guya manhwa18cc weebcentral atsumaru \
            qiscans mangaball madaradex hentainexus; do
  cp "<termux-package>/plugins/$name.so" "$DEST/lib$name.so"
done
# ...or from a fresh extension-workspace build (step 4):
for so in target-extensions/aarch64-linux-android/release/lib*.so; do
  cp "$so" "$DEST/$(basename "$so")"
done
```

The plugin files must keep the `lib*.so` name; the installer only extracts
those as native libraries. The APK build then picks everything up
automatically; no Gradle or Kotlin change is needed.

---

## Verifying the results

Run these checks on every binary before committing it:

```bash
cd android/app/src/main/jniLibs/arm64-v8a

# 1. Architecture: every file must be an aarch64 ELF shared object
file *.so
#   expected: ELF 64-bit LSB shared object, ARM aarch64, ...

# 2. Dynamic dependencies: the server needs libarchive.so, liblog.so and the
#    normal libc/libm/libdl; libarchive.so itself needs only libc/libdl
#    (zlib is statically linked); plugins need only libc/libm/libdl
readelf -d libtanoshi.so | grep NEEDED
readelf -d libarchive.so | grep NEEDED

# 3. No hardcoded RPATH/RUNPATH: the app supplies LD_LIBRARY_PATH at runtime
readelf -d libtanoshi.so | grep -E 'RPATH|RUNPATH'   # expect no output

# 4. 16 KB page alignment (Android 15+ devices with 16 KB pages)
readelf -lW libtanoshi.so | grep LOAD
#   expected alignment: 0x4000 in the last column

# 5. The embedded web UI is the infinite-scroll dist (v0.2.0 and later)
grep -c -a "catalogue-scroll-sentinel" libtanoshi.so   # expect 1
```

And on the built APK:

```bash
unzip -l app-debug.apk | grep 'lib/arm64-v8a'
#   all eleven .so files must be present
zipalign -c -P 16 4 app-debug.apk   # Android 15+ 16 KB alignment check
```

If either alignment check reports `0x1000`, rebuild with
`-Wl,-z,max-page-size=16384` (see the environment section) and re-copy.

---

## How the checked-in binaries were produced

The files committed in `android/app/src/main/jniLibs/arm64-v8a/` are the
output of this pipeline, built on an x86_64 Linux host with:

- Android NDK r26+ (aarch64 clang wrapper for API 26),
- Rust 1.99.1 with the `aarch64-linux-android` target,
- upstream Tanoshi checked out at the tag in `VERSION` (`0.40.0`) and patched
  with the Termux patch set + `native/patches/0001-android-app.patch`,
- the embedded web UI from `android/native/web-dist/` (package-3 dist with the
  infinite-scroll patch),
- libarchive **3.8.11**, built with zlib linked statically and SONAME
  `libarchive.so`,
- the plugin ABI pinned to `LIB_VERSION` (`0.38.0`); plugin files are named
  `lib<plugin>.so`.

`sync_prebuilt.sh` does not rebuild anything — it only copies what the other
scripts produced, so `git diff` after a rebuild shows exactly which binaries
changed.

### Reproducibility caveats

Bit-for-bit identical binaries are **not** expected between rebuilds:

- The extension workspace is built from its default branch unless a ref is
  pinned, so upstream may have moved.
- Cargo resolves dependencies from the lock files; a `Cargo.lock` update or a
  different Rust/NDK toolchain changes code generation.
- The web UI is the vendored package-3 dist; its hashed asset names and
  embedded build output change if it is regenerated with another
  `trunk`/`wasm-bindgen`/`sass`/`wasm-opt` toolchain.
- Timestamps and absolute build paths can end up in binaries and archives.

Functionally equivalent is what matters: the server version, the
`tanoshi-lib` ABI that plugins must match, and the checks above. When you
rebuild, keep `VERSION`, `LIB_VERSION` and the bundled plugin versions in
sync, and commit the whole `jniLibs/arm64-v8a/` directory in one change.

---

## Troubleshooting

**`Unable to find libarchive`** during `cargo build` — the vendored
`compress-tools` uses `pkg-config`. Export `PKG_CONFIG_ALLOW_CROSS=1` and
`PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"` (pointing at the
`cmake --install` prefix), and confirm the file
`$PREFIX/lib/pkgconfig/libarchive.pc` exists.

**`error: linking with ... failed` / `multiple definition of ...`** — the
Termux patch set requires `-Clink-arg=-Wl,--allow-multiple-definition` in
`RUSTFLAGS`, as shown above.

**`error[E0463]: can't find crate for core`** — the
`aarch64-linux-android` target is not installed: `rustup target add
aarch64-linux-android`.

**`wasm-bindgen` version mismatch when running `trunk`** — install exactly
`cargo install wasm-bindgen-cli --version 0.2.129 --locked` (the version
pinned by the upstream web workspace).

**Plugins do not load in the app** — the plugin's embedded `tanoshi-lib`
version differs from the server's (0.38.0). Rebuild both from the same
`Cargo.lock` state, then reinstall the APK.

**`cannot execute: required file not found` / `Permission denied` when the
server starts** — the binary was not packaged as a native library. The
installer only extracts files whose names match `lib*.so` from
`jniLibs/<abi>/`, so keep `libtanoshi.so`, `libarchive.so` and `lib<plugin>.so`
naming.
