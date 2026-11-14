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
There is still no per-plugin update mechanism: as of 0.2.0 a new/updated
plugin ships inside a new APK and reaches users through the in-app updater
(A19), not through the server's extension downloader.

### A7 — The first administrator is created through GraphQL `register`

No default credentials are baked in. On first run the app shows a dialog and
calls `mutation { register(login: {username, password}, isAdmin: true) }`
against `http://127.0.0.1:<port>/graphql`, after checking
`{ serverStatus { activated } }`. Skipping the dialog leaves registration to
the web UI.

*If wrong:* first-run onboarding breaks; no data is lost, the user can
register in the browser.

### A8 — arm64-v8a only

The only ABI shipped is `arm64-v8a` (`minSdk` 26), pinned in
`app/build.gradle.kts` with `ndk.abiFilters`. There is no `armeabi-v7a`,
`x86` or `x86_64` build, and the APK is not an ABI split or bundle.

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

The documented and CI-built artifact is the **debug** APK
(`applicationId dev.spainclaro.tanoshi.debug`), signed with the committed
shared sideload key `android/keystore/tanoshi-signing.jks` (A18). Distribution
is GitHub releases tagged `android-v*` plus sideloading only — there is no Play
Store channel.

*If wrong:* Play distribution would need a Play-compliant signing setup (Play
App Signing), a privacy policy, target-SDK policy compliance and an FGS
declaration review.

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
storage enabled), while "Open in browser" uses an `ACTION_VIEW` intent. As of
0.2.0 the embedded UI is the vendored `android/native/web-dist/` package-3
dist, built with `patches/tanoshi-infinite-scroll.patch`: the catalogue
auto-loads the next page via the `catalogue-scroll-sentinel` observer, with
**Load More** as the manual fallback.

*If wrong:* the WebView could need additional configuration for a future UI
feature; the browser path is unaffected. Note that changing web-UI behaviour
requires rebuilding `libtanoshi.so` (and shipping a new APK), not just
rebuilding the Android app, and that the web UI keeps its own theme.

### A15 — Data is app-private and not backed up

`allowBackup="false"` and app-private storage mean uninstalling the app
deletes the library/accounts. Moving data requires copying the
`files/tanoshi/` tree out (debug builds can use `adb run-as`).

*If wrong:* users lose their library on uninstall. This matches the Termux
package's "back up `data/`" warning; a future export/import feature is a
possible enhancement (see Q7).

Upgrading from **0.1.0 to 0.2.0 requires exactly this one-time uninstall**
because the signing key changed (A18); copy `files/tanoshi/` out first if the
library matters.

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
install the NDK/Rust. CI builds on pushes and pull requests, and creates a
GitHub release (with the debug APK) only for **`android-v*`** tags; plain `v*`
Termux package tags do not trigger the workflow at all (see Q6). For a tag,
CI derives `versionName` from `android-vX.Y.Z`, sets
`versionCode = X * 10000 + Y * 100 + Z`, and names the APK
`tanoshi-mobile-server-release-<UTC date>.apk` via `-PapkDate`, passing all
three to Gradle; local builds use the Gradle defaults (0.3.0 / 3, local date).

*If wrong:* if the repository owner prefers binaries rebuilt in CI, the
workflow becomes much heavier (NDK, Rust, libarchive, wasm tooling) — see Q9.

### A18 — All releases share one committed sideload signing key

`android/keystore/tanoshi-signing.jks` (JKS, alias `tanoshi`, store/key
password `tanoshi-android`) is committed on purpose. `app/build.gradle.kts`
signs both the `debug` and `release` build types with it when the file is
present, and falls back to the local Android debug keystore when it is not.
This is what makes the in-app updater possible: Android only replaces an
installed app when the new APK has the same signing key, and CI and local
builds both need one stable signature. The key is not a secret — it protects
nothing on a sideload-only app — it only has to stay stable.

0.1.0 was built with an ephemeral CI debug key, so **0.1.0 users must
uninstall once** before installing 0.2.0 or later (losing app data). Replacing
the key
for a private build has the same consequence. Details and `keytool` commands:
[`keystore/README.md`](keystore/README.md).

*If wrong:* in-app updates would fail with a signature mismatch, or users
would have to uninstall (and lose their library) on every key change.

### A19 — In-app updates are manual, full-APK downloads from GitHub releases

`Settings → Updates` (`UpdateManager`) queries
`https://api.github.com/repos/spainclaro-debug/tanoshi-termux/releases?per_page=50`
without authentication and considers only non-draft, non-prerelease tags
starting with `android-v` that have an `.apk` asset. It prefers asset names
starting with `tanoshi-mobile-server` (the 0.3.0+ naming), accepts any other
`.apk` as a fallback, and still understands the legacy `app-debug.apk` name
used by 0.1.0/0.2.0. It compares the tag version numerically with the installed
`versionName` (the debug `-debug` suffix is stripped), downloads the APK into
`cacheDir/updates/` (temporary `.part` file, size checked, SHA-256 checked
when the release asset exposes a digest), and installs it via
`PackageInstaller` after the system confirmation dialog. This requires
`REQUEST_INSTALL_PACKAGES` and the user-granted "Install unknown apps"
permission; the app also needs network access and is subject to GitHub's
unauthenticated rate limit.

Deliberate limits: **no automatic/background update check**, **no silent
install**, **no delta/incremental updates** (every update downloads the full
APK), no Play Store channel, and only releases signed with the shared key
(A18) can replace the app. The install stops the running app/server; the user
reopens Tanoshi afterwards.

*If wrong:* users would expect background/automatic updates; the fallback is
a manual APK download from the release page.

### A20 — The app version is independent of the Tanoshi server version

The app follows its own `android-vX.Y.Z` version scheme (0.3.0 / versionCode
3 locally; versionCode `X*10000+Y*100+Z` for tagged releases, so
`android-v0.3.0` is 300), independent of the bundled Tanoshi server (0.40.0 /
`VERSION`) and of the Termux package (`PACKAGE_TAG`). An app update may or may
not change the bundled server; the two version lines are documented
separately.

*If wrong:* users could mistake app releases for server/extension releases;
the docs state both versions explicitly in the Versions table and changelog.

### A21 — LAN sharing uses a discovered IPv4 address over plain HTTP

The Server screen's **Share on your network** card shows a QR code encoding
`http://<host-ipv4>:<port>` only when the server is `RUNNING`, bound to
`0.0.0.0`, and a usable LAN address was found. The address is discovered by
taking the first IPv4 address of the active network when `ConnectivityManager`
reports it as Wi-Fi or Ethernet, and otherwise falling back to enumerating
non-loopback interfaces; loopback and link-local (`169.254.x.x`) IPv4
addresses are excluded, and the address is re-resolved when the screen
resumes. The QR is generated with ZXing core 3.5.3 (pure Java, no native
code) off the main thread. The card also
shows `ip:port` with a copy button, warns that it only works on the same
network and that the connection is plain HTTP, and merely describes what to
enable when requirements are missing — it never changes the bind address.

*If wrong:* the QR may encode an unreachable address (VPN, VLAN, hotspot
isolation) or the wrong interface; the docs state the same-network/plain-HTTP
limits and the app falls back to the existing LAN guidance text.

### A22 — Release APKs use a dated filename

Since 0.3.0, app builds produce
`tanoshi-mobile-server-release-<yyyy-MM-dd>.apk` via the Gradle
`androidComponents.onVariants` output rename. CI sets `-PapkDate` from the UTC
date; local builds default to the local date. The workflow's artifact name
stays `tanoshi-android-debug-apk`, but the file inside it and the GitHub
release asset use the dated name. The in-app updater prefers asset names
starting with `tanoshi-mobile-server` and still accepts legacy
`app-debug.apk` assets, so 0.1.0/0.2.0 releases and manual downloads remain
valid. Historical releases keep their original `app-debug.apk` name.

*If wrong:* users/automation looking for `app-debug.apk` in new releases will
not find it; the docs and updater cover both names.

### A23 — Settings can be saved before the first server start

Since 0.3.0, `ConfigManager.setPort` calls `AppPaths.ensureDirectories` and
`writeAtomically` creates the target's parent directory defensively. This
fixes the 0.2.0-and-earlier `config.yml.tmp: open failed: ENOENT` failure when
the port or `config.yml` was saved before the server had ever run. Existing
`config.yml`/data are untouched by the fix.

*If wrong:* the first settings save before starting the server would fail
again; the docs troubleshooting section describes the old symptom.

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
app). A Play Store release would need: a Play-compliant signing setup, privacy
policy, data-safety declaration, target-SDK/FGS policy compliance (including
the `specialUse` declaration review), and possibly removal of
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

**Decided (owner, 2026-11-14):** sideload-only for now; GitHub releases tagged
`android-v*` (consumed by the in-app updater, A19) are the update channel.
A Play Store release remains out of scope.

### Q5 — Who owns the release signing key, and where does it live?

**Resolved (owner, 2026-11-14):** the key lives in the repository at
`android/keystore/tanoshi-signing.jks` (alias `tanoshi`, password
`tanoshi-android`) and is used by CI and local builds alike, so in-app updates
work. It is a stable sideload key, not a secret; there is no separate
production key while the app is sideload-only. A future Play Store release
would use Play App Signing instead. See A18 and
[`keystore/README.md`](keystore/README.md).

### Q6 — Should app releases share the Termux package's version tags?

**Resolved (owner, 2026-11-14):** no. Android releases use a distinct
**`android-v*`** tag (0.1.0, 0.2.0, ...); the Termux package keeps its `v*`
tags such as `v0.40.0-termux.3`. The workflow triggers only on `android-v*`
tags (plus ordinary pushes and pull requests), so a Termux tag can neither
start it nor have files attached to its release.

### Q7 — Do you want data export/import (backup) in the app?

Uninstall currently deletes the library (A15). A simple export/import of
`files/tanoshi/` (ZIP via SAF or share sheet) would make upgrades/migrations
safe. This is a feature decision, not a bug.

### Q8 — Should LAN exposure stay a single toggle?

Today the toggle switches between `127.0.0.1` and `0.0.0.0`. Alternative
options the owner may want: a custom bind address, per-interface selection,
or a separate "allow LAN login" warning/confirmation step. Confirm the
desired UX and whether the plain-HTTP warning is enough.

**Update (0.3.0):** the Server screen's
[QR share card](README.md) now surfaces LAN sharing only while the server
is actually reachable on `0.0.0.0`, but the underlying setting is still the
single Expose on LAN toggle. Confirm that this is the desired UX (and whether
a per-interface choice is still wanted).

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
downloaded code from app storage is forbidden on Android 10+.

**Resolved in practice (0.2.0):** new/updated plugins ship inside a new APK and
reach users through the in-app updater (A19), which replaces the whole app.
There is no per-plugin update path (and no in-server source rebuild, since
that needs a Rust toolchain on the device).

### Q12 — Is the current README default-login section still correct for the APK?

The root README documents `admin` / `tanoshi123` for the Termux package; the
APK has no default account (A7). The new "Android app (no Termux)" section
calls this out, but confirm the final user-facing wording and whether the
Termux default login should change as well.

### Q13 — Should updates be automatic or incremental?

**Resolved (owner, 2026-11-14):** no. The updater is user-initiated (a
**Check for updates** button in Settings), downloads the full release APK, and
never installs without Android's confirmation dialog. There is no background
update service and no delta/incremental patching — the simplest and safest
model for a sideloaded, self-hosted app (see A19).

### Q14 — Should release APKs keep a generic `app-debug.apk` name?

**Resolved (owner, 0.3.0):** no. New builds are named
`tanoshi-mobile-server-release-<yyyy-MM-dd>.apk` (UTC date in CI, local date
otherwise) so downloads are self-describing. The in-app updater accepts both
the new prefix and legacy `app-debug.apk` assets, and the 0.1.0/0.2.0 release
assets keep their historical name. See A22.
