Patch set for running Tanoshi on Termux / Android
==================================================

The server is written for glibc Linux. Termux is Bionic, and a few assumptions
in upstream do not hold. Every change below is guarded so that non-Android
platforms keep upstream behaviour.

Files
-----

tanoshi-android.patch
    Applies to the Tanoshi source tree (git apply). Contains:

    1. crates/tanoshi-vm/src/lib.rs
       PLUGIN_EXTENSION is only defined for windows/macos/linux, so Android
       fails to compile. Adds target_os = "android" to the "so" arm.

    2. crates/tanoshi/bin/tanoshi.rs
       Installs the rustls "ring" crypto provider explicitly. On Android the
       dependency tree enables both ring and aws-lc-rs, so rustls cannot choose
       automatically and panics at startup.

    3. crates/tanoshi/src/infrastructure/http.rs  (new file)
    4. crates/tanoshi/src/infrastructure/mod.rs
       Shared HTTP client builder. reqwest defaults to rustls-platform-verifier,
       which needs the Android Java runtime to read the system trust store. A
       Termux CLI process has no such runtime, so every HTTPS request panics with
       "Expect rustls-platform-verifier to be initialized". On Android the
       builder pins the bundled Mozilla (webpki) roots instead.

    5. crates/tanoshi/src/application/worker/updates.rs
    6. crates/tanoshi/src/infrastructure/domain/repositories/source.rs
    7. crates/tanoshi-vm/src/extension/manager.rs
       Route the update worker, the source repository and the extension
       downloader through those helpers. The last one also rejects non-2xx
       downloads and rebuilds the extension from source when the repository has
       no Android binary (see build.rs below).

    8. crates/tanoshi-vm/src/extension/build.rs  (new file)
    9. crates/tanoshi-vm/src/extension/mod.rs
       Extension source builder. The upstream extension repository publishes
       only glibc Linux and Windows plugins, so the Android URL 404s and the
       14-byte error page was written out as a plugin. This module detects the
       failure, clones luigi311/tanoshi-extensions, builds the requested
       extension natively with the local toolchain, and feeds it back into the
       normal install path.

   10. crates/tanoshi/src/presentation/rest/image.rs
       The /image route discarded every error as a bare 500. It now logs the
       cause.

   11. Cargo.toml files for tanoshi, tanoshi-vm, tanoshi-tracker and
       tanoshi-notifier
       Adds rustls + webpki-roots as Android-only dependencies.
       Cargo.lock changes only because of the compress-tools patch.

http.rs.new
extension-build.rs.new
    Full contents of the two files the patch adds. `git diff` cannot carry new
    files, so the build script copies them into place.

networking-helpers.patch
    Applies to the extensions workspace (luigi311/tanoshi-extensions), not to
    the server. Adds the networking helpers the ported extensions need:
    a JSON POST, form/header requests that also return Set-Cookie, and an image
    GET that can send an explicit Cookie header (needed because MadaraDex's
    auth cookie is scoped to .madaradex.org and is not replayed to its CDN).

tanoshi-infinite-scroll.patch
    Applies to the Tanoshi source tree. The catalogue web UI paginates with a
    "Load More" button; this patch adds an IntersectionObserver on a sentinel
    below the grid so the next page is fetched automatically ~600px before the
    reader reaches the bottom. The button stays as a manual fallback and turns
    into "No more results" once a page comes back empty. Also enables the
    web-sys IntersectionObserverInit feature.

extension-icons.patch
    Applies to the extensions workspace. Replaces the MangaDex icon URL:
    mangadex.org answers every favicon path with an HTML bot-challenge page, so
    the web UI showed a broken image. Points at the public favicon mirror
    instead. Optional — the build continues if upstream has moved on.

vendor/compress-tools-0.16.1/
    A vendored copy of the compress-tools crate with three changes, wired in via
    [patch.crates-io] in the build's cargo config:

    - drops libb2 (BLAKE2) from the blanket "static" feature list; Termux has no
      libb2 and its libarchive needs none
    - probes shared libraries instead of static-only ones
    - teaches the locale guard about target_os = "android"

Applying by hand
----------------

In a checkout of the tag you want:

    git apply patches/tanoshi-android.patch
    cp patches/http.rs.new crates/tanoshi/src/infrastructure/http.rs
    cp patches/extension-build.rs.new crates/tanoshi-vm/src/extension/build.rs

    # cargo config
    mkdir -p "$HOME/.tanoshi-build/cargo-home"
    cat >"$HOME/.tanoshi-build/cargo-home/config.toml" <<EOF
    [patch.crates-io]
    compress-tools = { path = "<package>/vendor/compress-tools-0.16.1" }
    EOF

Then build the web UI, then the server:

    cargo build -p tanoshi --release   # with RUSTFLAGS=-Clink-arg=-Wl,--allow-multiple-definition

If upstream changes the files these touch, `git apply` will fail. The build
script reports this instead of silently producing a broken binary.
