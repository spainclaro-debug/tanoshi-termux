# Android app — assumptions and open questions

This document lists the assumptions the standalone Android app (the
`android/` project) is built on, and the decisions the repository owner should
confirm. It complements [`README.md`](README.md) (build/usage) and
[`native/README.md`](native/README.md) (native pipeline).

Legend: **A** = assumption we built on, **Q** = question for the owner.
Each assumption notes where it comes from and what breaks if it is wrong.

---

## Assumptions

### A1 — Upstream versions are pinned: Tanoshi 0.40.0 / `tanoshi-lib` 0.38.0

The server is built from `luigi311/tanoshi` **v0.40.0** (the root `VERSION`
file) and the extension plugins are built against `tanoshi-lib`
**0.38.0** (the root `LIB_VERSION` file). Plugins embed that ABI version and
refuse to load on a mismatch.

*If wrong:* the bundled plugins would not load (plugin count 0 in the web UI)
and the app would still run but with no sources. Fix by rebuilding server and
plugins from the same upstream state.

### A2 — Data lives under `TANOSHI_HOME`

Tanoshi reads its data directory from `TANOSHI_HOME`; the app sets it to
`filesDir/tanoshi/`. Everything the server writes — `config.yml`,
`tanoshi.db`, `downloads/`, `cache/`, `manga/`, `plugins/`, `server.log` —
stays below that directory, i.e. in app-private storage.

*If wrong:* data would leak into other paths; the app is sandboxed, so the
practical effect would be a server that cannot find/create its files.

### A3 — `config.yml` schema is the current upstream one

The app generates the same schema the Termux package writes: `base_url`,
`port`, `database_path`, `create_database`, `secret`, `update_interval`,
`max_concurrent_update_sources`, `auto_download_chapters`, `plugin_path`,
`extension.*`, `local_path`, `download_path`, `cache_path`,
`enable_playground`, and the notification blocks (`telegram`, `pushover`,
`gotify`, `myanimelist`, `anilist`). Paths are absolute and literal — Tanoshi
does not expand `~` or environment variables.

*If wrong:* upstream added/renamed keys; the server fills in defaults for
missing ones (it already does for the Termux package), but the app's
`config.yml` editor only validates `port` and `secret`.

### A4 — `secret` must be exactly 16 characters

Tanoshi uses it as an AES-128-CBC key, so the app generates a 16-character
alphanumeric secret and repairs (rotates) any other length on start. Rotating
it invalidates all existing sessions.

*If wrong:* cover-image URLs fail to decrypt; the server must generate a new
secret and users log in again.

### A5 — Upstream binds `0.0.0.0`; the Android build adds `TANOSHI_BIND`

Upstream hardcodes the listen address. The Android-app patch
(`native/patches/0001-android-app.patch`) makes the server read
`TANOSHI_BIND` instead. The app defaults to **`127.0.0.1`** and sets
**`0.0.0.0`** only while "Expose on LAN" is enabled.

*If wrong:* the app could be reachable on the LAN even with the toggle off
(security regression), or LAN mode would not work.

### A6 — Plugins are bundled, read-only, and not updatable in-app

The nine plugins are compiled into the APK as `lib<name>.so` and loaded from
`nativeLibraryDir`. The Android patch skips upstream's staging copy into the
writable plugin directory (Android 10+ cannot `dlopen` from app-writable
storage). The writable `plugin_path` directory is still used for extension
preferences.

*If wrong:* plugins either fail to load or fail to persist preferences.
There is currently no in-app "update extensions" feature; a new/updated
plugin means a new APK.

### A7 — The first administrator is created through GraphQL `register`

No default credentials are baked in. On first run the app shows a dialog and
calls `mutation { register(login: {username, password}, isAdmin: true) }`
against `http://127.0.0.1:<port>/graphql`, after checking
`{ serverStatus { activated } }`. Skipping the dialog leaves registration to
the web UI.

*If wrong:* first-run onboarding breaks; no data is lost, the user can
register in the browser.

### A8 — arm64-v8a only

The only ABI shipped is `arm64-v8a` (`minSdk` 26). There is no
`armeabi-v7a`, `x86` or `x86_64` build, and the APK is not an ABI split or
bundle.

*If wrong:* 32-bit-only and emulator devices cannot install the app. See Q2.

### A9 — No TLS; HTTP only

The server speaks plain HTTP and the app is configured to allow cleartext
only to loopback (`127.0.0.1`, `localhost`, `10.0.2.2` emulator). LAN
exposure therefore exposes the login page and session over plain HTTP.

*If wrong:* the app would need a TLS terminator (reverse proxy) or an
in-app HTTPS stack, which is out of scope for now. This is intentional and
documented in the security notes.

### A10 — Foreground service type `specialUse`

The service uses `foregroundServiceType="specialUse"` with
`FOREGROUND_SERVICE_SPECIAL_USE` and the
`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property rather than `dataSync`, because
Android 15+ caps `dataSync` at 6 h/day. A partial wake lock is held while the
server runs.

*If wrong (Play Store policy):* Google may reject the `specialUse` justification
for a sideloaded/self-hosted app; see Q4.

### A11 — The APK is sideloaded, not distributed via Play Store

The documented and CI-built artifact is the **debug** APK signed with the
standard debug keystore (`applicationId dev.spainclaro.tanoshi.debug`).
Release signing requires the owner's keystore; the committed release build
type currently reuses the debug signing config only as a placeholder.

*If wrong:* Play distribution needs release signing, a privacy policy,
target-SDK policy compliance and an FGS declaration review.

### A12 — Native libraries must be extracted from the APK

`android:extractNativeLibs="true"` and
`packaging.jniLibs.useLegacyPackaging = true` are required so the installer
extracts `libtanoshi.so`/`libarchive.so`/plugins into the executable
`nativeLibraryDir`. `LD_LIBRARY_PATH` includes `nativeLibraryDir` so the
server finds `libarchive.so`.

*If wrong:* the server cannot be executed (`Permission denied`) or fails to
resolve `libarchive.so`; both are documented troubleshooting cases.

### A13 — 16 KB page alignment is required for Android 15+ devices

The native libraries are linked with `-Wl,-z,max-page-size=16384`. Android 15
can run on devices with 16 KB memory pages; 4 KB-aligned binaries fail to
load there.

*If wrong:* the app crashes with `dlopen`/linker errors on such devices only.
This must be re-checked on every native rebuild.

### A14 — The web UI is embedded and shown in a WebView

Tanoshi's wasm web UI is embedded into the server binary; the app's "Open in
app" screen is a `WebView` pointed at the loopback URL (JavaScript + DOM
storage enabled), while "Open in browser" uses an `ACTION_VIEW` intent.

*If wrong:* the WebView could need additional configuration for a future UI
feature; the browser path is unaffected.

### A15 — Data is app-private and not backed up

`allowBackup="false"` and app-private storage mean uninstalling the app
deletes the library/accounts. Moving data requires copying the
`files/tanoshi/` tree out (debug builds can use `adb run-as`).

*If wrong:* users lose their library on uninstall. This matches the Termux
package's "back up `data/`" warning; a future export/import feature is a
possible enhancement (see Q7).

### A16 — The Termux package remains authoritative for the binary

The checked-in `jniLibs/arm64-v8a/` binaries are built from the same upstream
source and patch set as the Termux package (`patches/`,
`vendor/compress-tools-0.16.1/`, `lib/build.sh`), via
`android/native/build_tanoshi.sh`, `build_libarchive.sh` and
`sync_prebuilt.sh`. `libtanoshi.so` is the patched Tanoshi 0.40.0 binary;
`libarchive.so` is a minimal libarchive **3.8.11** build with zlib linked in
statically (only libc/libdl `NEEDED`) and SONAME patched to `libarchive.so`;
plugin files are named `lib<plugin>.so`.

*If wrong:* the two deliverables would silently diverge in server/plugin
version; the docs tell maintainers to bump both together.

### A17 — CI only needs the Gradle build

The native binaries are committed, so the GitHub Actions workflow installs
JDK 17 + Android SDK and runs `./gradlew :app:assembleDebug`; it does not
install the NDK/Rust. CI triggers on `v*` and `android-v*` tags but attaches
the debug APK to GitHub releases only for **`android-v*`** tags (see Q6).

*If wrong:* if the repository owner prefers binaries rebuilt in CI, the
workflow becomes much heavier (NDK, Rust, libarchive, wasm tooling) — see Q9.

---

## Open questions for the repository owner

### Q1 — What should the default bind address be?

We default to **loopback only** (`127.0.0.1`), matching the Termux package's
security note ("only expose when needed"), with a LAN toggle. The Termux
package binds `0.0.0.0` by default, so the two deliverables behave
differently. **Decided (owner, 2026-11-14): loopback default + explicit LAN
toggle stays.**

### Q2 — Should 32-bit or emulator ABIs be bundled?

Only `arm64-v8a` is built. `armeabi-v7a` (old 32-bit phones) and
`x86_64` (emulators) would require rebuilding the server, libarchive and all
plugins for each ABI and roughly doubling/tripling APK size and CI time.
Confirm arm64-only, or specify which extra ABIs are required.

### Q3 — Should the app auto-create a default administrator?

Currently the first admin is created interactively (no baked credentials),
which is safer than Termux's `admin`/`tanoshi123` default. If install-time
provisioning without a dialog is desired (e.g. for a fixed kiosk setup),
confirm the expected behaviour and credentials policy — a baked default
password would have to be called out in the docs and changed on first login.
**Decided (owner, 2026-11-14): no baked-in admin; keep the first-run
dialog.**

### Q4 — Sideload only, or Play Store?

Sideload-only is assumed (debug APK, `specialUse` FGS, self-hosted scraper
app). A Play Store release would need: a release signing key, privacy policy,
data-safety declaration, target-SDK/FGS policy compliance (including the
`specialUse` declaration review), and possibly removal of
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Confirm the distribution target.

### Q5 — Who owns the release signing key, and where does it live?

The repo builds debug APKs. A production release needs the owner's keystore
(and, if CI signs it, encrypted secrets). Once published, that key cannot be
changed without users uninstalling (losing app data). Decide the key owner,
alias and whether CI should ever hold it.

### Q6 — Should app releases share the Termux package's version tags?

**Resolved (owner, 2026-11-14):** no. Android releases use a distinct
**`android-v*`** tag, starting with `android-v0.1.0` (matching the app's
`versionName`); the Termux package keeps its `v*` tags such as
`v0.40.0-termux.3`. The workflow still triggers a build on `v*` tags but only
publishes a GitHub release for `android-v*`, so it can never modify or attach
files to a Termux package release.

### Q7 — Do you want data export/import (backup) in the app?

Uninstall currently deletes the library (A15). A simple export/import of
`files/tanoshi/` (ZIP via SAF or share sheet) would make upgrades/migrations
safe. This is a feature decision, not a bug.

### Q8 — Should LAN exposure stay a single toggle?

Today the toggle switches between `127.0.0.1` and `0.0.0.0`. Alternative
options the owner may want: a custom bind address, per-interface selection,
or a separate "allow LAN login" warning/confirmation step. Confirm the
desired UX and whether the plain-HTTP warning is enough.

### Q9 — Should CI rebuild the native binaries?

The workflow builds only the app because the binaries are committed (A17).
If the owner wants reproducible/audited builds from source in CI, a second
(heavier) workflow would install NDK r26+, Rust 1.99.1, wasm tooling and
libarchive, then run the `native/` scripts and build the APK. Confirm whether
that is in scope.

### Q10 — Should Termux and Android binary builds be unified?

Both use the same patches but different build environments (Termux native
build vs. NDK cross-compile). Keeping two pipelines risks drift; unifying
them (or at least sharing version pins) is worth deciding after the first
successful APK release.

### Q11 — How should extension updates be delivered?

Plugins are frozen in the APK (A6). The upstream Tanoshi server can download
prebuilt extensions, but upstream publishes no Android binaries, and loading
downloaded code from app storage is forbidden on Android 10+. Options:
ship APK updates for plugin changes, or keep the Termux-style in-server
source rebuild (which needs Rust on device — not available in the app).
Confirm the expected extension update story.

### Q12 — Is the current README default-login section still correct for the APK?

The root README documents `admin` / `tanoshi123` for the Termux package; the
APK has no default account (A7). The new "Android app (no Termux)" section
calls this out, but confirm the final user-facing wording and whether the
Termux default login should change as well.
