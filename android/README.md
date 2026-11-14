# Tanoshi for Android (standalone APK)

A standalone Android app that bundles the same [Tanoshi](https://github.com/luigi311/tanoshi)
manga server and the same nine extensions as the Termux package in the
repository root — **without Termux**. The app runs the server as a foreground
service and shows its web UI in an embedded WebView (or your normal browser).

Current release: **0.4.0** — see the
[latest release](https://github.com/spainclaro-debug/tanoshi-termux/releases/latest)
and [`CHANGELOG.md`](CHANGELOG.md). It makes the background server harder to
kill: a hidden, inaudible **silent-audio keep-alive** puts the app in a
media-playback foreground state, and an **auto-restart watchdog** brings the
server back after an unexpected kill (up to 5 times in 10 minutes). The APK
name is unchanged (`tanoshi-mobile-server-release-<yyyy-MM-dd>.apk`). It
includes the 0.3.0 features (LAN QR sharing, port-save fix, dated APK naming)
and the 0.2.0 features (catalogue infinite scroll, in-app updates,
Dark/Light/Nord/Catppuccin themes, shared signing key).

This directory is the Android Gradle project (`dev.spainclaro.tanoshi`).
The `arm64-v8a` native binaries are prebuilt and checked in, so building the
APK only needs JDK 17 and the Android SDK — no Rust or NDK. See
[`native/README.md`](native/README.md) to rebuild the native pieces.

> **Sideload only, arm64-v8a only, HTTP only.** There is no Play Store build,
> no TLS and no default account. Read the [Security notes](#security-notes)
> before exposing it on a LAN. **Users of 0.1.0 must uninstall it once before
> installing 0.2.0 or later** (different signing key) — see
> [Installing and updating](#installing-and-updating).

---

## Contents

- [What you get](#what-you-get)
- [What's new in 0.4.0](#whats-new-in-040)
- [Requirements](#requirements)
- [Building the APK](#building-the-apk)
- [Installing and updating](#installing-and-updating)
- [First run](#first-run)
- [Using the app](#using-the-app)
- [Sharing on your network (QR)](#sharing-on-your-network-qr)
- [The embedded web UI](#the-embedded-web-ui)
- [Themes](#themes)
- [In-app updates](#in-app-updates)
- [Architecture](#architecture)
- [Keeping the server alive in the background](#keeping-the-server-alive-in-the-background)
- [Storage layout](#storage-layout)
- [Settings and `config.yml`](#settings-and-configyml)
- [Native binaries and rebuilding them](#native-binaries-and-rebuilding-them)
- [Security notes](#security-notes)
- [Troubleshooting](#troubleshooting)
- [Versions](#versions)
- [Project layout](#project-layout)
- [Changelog](#changelog)
- [Credits and licences](#credits-and-licences)

---

## What you get

- The Tanoshi **v0.40.0** server, built from upstream `luigi311/tanoshi` with
  this repository's Termux patch set plus the Android-app patch, running as an
  `aarch64` native process inside the app's sandbox.
- **Nine extensions** bundled as native plugins: Mangadex, Guya, Manhwa18cc,
  WeebCentral, Atsumaru, QiScans, Manga Ball, MadaraDex and HentaiNexus.
- The same embedded Tanoshi web UI as the Termux package, including the
  **infinite-scroll catalogue**: the next page loads automatically as you
  scroll, with **Load More** as the manual fallback.
- A small Material 3 wrapper: start/stop the server, watch its logs, open the
  web UI, edit the port, toggle LAN exposure and share a QR code for other
  devices on the same network, inspect/edit `config.yml`, pick an app theme
  and update the app from inside it.
- The entire server data directory lives in the app's private storage; the
  phone needs no root, no Termux and no shell.

The APK is large because it carries the ~35 MB server binary plus `libarchive`
and the plugins. It is not split by ABI — only `arm64-v8a` is shipped.

---

## What's new in 0.4.0

- **Hidden silent-audio keep-alive** — while the server runs, the app loops a
  generated, inaudible one-second WAV through `MediaPlayer` at volume 0 and
  declares the foreground service as `specialUse|mediaPlayback`. Android/OEMs
  treat the app as actively playing media, so the app and its native server
  child processes are less likely to be frozen or killed. There is **no
  MediaSession and no extra notification** — the only notification is still
  the normal server one. It is a best-effort mitigation, not a guarantee; see
  [Keeping the server alive in the background](#keeping-the-server-alive-in-the-background).
- **Auto-restart watchdog** — if the server process is killed while you want
  it running, the app now restarts it automatically (up to **5 times in a
  rolling 10-minute window**, about 3 s apart) instead of giving up at the
  first unexpected exit. When the budget is exhausted it shows the usual
  `ERROR` state.
- **Phantom-process-killer workaround documented** — the reliable user-side
  fixes (Developer options **Disable child process restrictions**, or the ADB
  `settings`/`device_config` commands), how to confirm from `logcat` that the
  killer is the cause, and the trade-offs. The silent audio improves the app's
  standing but does **not** exempt it from the Android 12+ phantom-process
  limits.
- **Version 0.4.0** — `versionCode` 4 in local builds; the `android-v0.4.0`
  tag produces 400. The APK filename scheme is unchanged. Includes the 0.3.0
  features (LAN QR sharing, port-save fix, dated APK naming) and the 0.2.0
  features (catalogue infinite scroll, in-app updates,
  Dark/Light/Nord/Catppuccin themes, stable signing key).

Full details: [`CHANGELOG.md`](CHANGELOG.md).

---

## Requirements

**To run the app**

- Android **8.0 (API 26)** or newer, on an **arm64 / aarch64** device.
  (32-bit and x86/x86_64 builds are not bundled — see
  [`ASSUMPTIONS_AND_OPEN_QUESTIONS.md`](ASSUMPTIONS_AND_OPEN_QUESTIONS.md).)
- ~250 MB free space for the app and a fresh library.
- To use **in-app updates**: a network connection and the "Install unknown
  apps" permission for Tanoshi (the app links to the system setting).

**To build the APK (app only)**

- **JDK 17** (`JAVA_HOME` pointing at a JDK 17; Gradle 8.9 + AGP 8.7.3 are
  configured for it).
- Android SDK with **Platform 35** and **Build-Tools 35.0.0**
  (`ANDROID_HOME` / `ANDROID_SDK_ROOT`, or `android/local.properties`).
- Android Studio **Ladybug (2024.2.1) or newer** if you prefer the IDE; its
  embedded JDK is 17-compatible.

**To rebuild the native binaries** you additionally need Android NDK r26+,
Rust 1.99.1 with the `aarch64-linux-android` target, CMake and
`pkg-config` — see [`native/README.md`](native/README.md). You do **not** need
any of that for a normal APK build.

---

## Building the APK

### Android Studio

1. Open the `android/` directory (not the repository root) as a project.
2. Let Gradle sync; select the embedded JDK 17 if prompted.
3. **Build → Build Bundle(s)/APK(s) → Build APK(s)**.

The debug APK is written to
`android/app/build/outputs/apk/debug/tanoshi-mobile-server-release-<yyyy-MM-dd>.apk`,
where `<yyyy-MM-dd>` is the local build date (CI passes the UTC date via
`-PapkDate`).

### Command line

```bash
cd android
./gradlew :app:assembleDebug
```

The first run downloads the Gradle 8.9 distribution and the Compose
dependencies; later builds are cached. Other useful tasks:

```bash
./gradlew :app:assembleRelease   # release variant (same shared signing key)
./gradlew :app:installDebug      # build + install on a connected device
./gradlew clean                  # remove build outputs
```

Local builds use the defaults in `app/build.gradle.kts` (**0.4.0**,
`versionCode` 4) and today's date in the APK filename. CI derives both from
the release tag; you can do the same manually:

```bash
./gradlew :app:assembleDebug -PappVersionName=0.4.0 -PappVersionCode=400 \
  -PapkDate=2026-11-14
```

`-PapkDate` is optional locally (the local date is used) and is what CI sets
from the UTC build date.

### Signing

The app is sideloaded only, so the signing key does not need to be a secret —
it only needs to be **stable**. Every release (CI and local) is signed with
the key committed at
[`keystore/tanoshi-signing.jks`](keystore/tanoshi-signing.jks):

| Field | Value |
| --- | --- |
| Keystore | `android/keystore/tanoshi-signing.jks` (JKS) |
| Key alias | `tanoshi` |
| Store / key password | `tanoshi-android` |

`app/build.gradle.kts` signs **both** the `debug` and `release` build types
with this key when the file exists, and falls back to the local Android debug
keystore (`~/.android/debug.keystore`) when it does not. CI builds the `debug`
variant, whose application id is `dev.spainclaro.tanoshi.debug`; the release
variant uses `dev.spainclaro.tanoshi`. In-app updates replace the installed
app, so they only work within the same package id — i.e. the published
`tanoshi-mobile-server-release-<date>.apk` replaces a previously installed
build of the `.debug` package. Installs made from the older
`app-debug.apk` name are replaced the same way.

Why commit a keystore? Android refuses to install an update over an app signed
with a different key, and the [in-app updater](#in-app-updates) hands the next
release APK to the system installer. A per-machine debug key would make every
build incompatible. The full rationale, key details and replacement
instructions are in [`keystore/README.md`](keystore/README.md).

**Upgrading from 0.1.0:** 0.1.0 was built with an ephemeral CI debug key, so
0.2.0 or later cannot be installed over it (Android reports a signature
mismatch). Uninstall 0.1.0 first — this deletes the app's private data. See
[Installing and updating](#installing-and-updating).

**Using your own key:** for a private build, replace
`android/keystore/tanoshi-signing.jks` (or point the `signingConfigs` block at
your own file and passwords) and rebuild. Changing the key breaks in-app
updates for already-installed builds: users must uninstall once. See
[`keystore/README.md`](keystore/README.md) for the `keytool` command and the
details.

### CI

[`.github/workflows/android-apk.yml`](../.github/workflows/android-apk.yml)
builds `:app:assembleDebug` on every push, on pull requests that touch
`android/**` or the workflow, and on manual dispatch. It installs JDK 17
(Temurin) and the Android SDK packages `platforms;android-35` and
`build-tools;35.0.0` with `sdkmanager`, runs Gradle with the wrapper, and
uploads the APK as the **`tanoshi-android-debug-apk`** artifact; the file
inside it is `tanoshi-mobile-server-release-<UTC date>.apk`. No third-party
Android setup/release actions are used: the SDK is provisioned with
`sdkmanager` and releases are published with the preinstalled GitHub CLI.

Releases are created only for tags of the form **`android-vX.Y.Z`**, for
example `android-v0.4.0`. For a tag, CI derives everything from the tag and
the build date:

- `versionName` = `X.Y.Z` from the tag;
- `versionCode` = `X * 10000 + Y * 100 + Z` (so `android-v0.4.0` → `400`);
- the APK filename date = the UTC build date (`-PapkDate=$(date -u +%F)`).

The build command passes these with `-PappVersionName`, `-PappVersionCode` and
`-PapkDate`, and the dated debug APK is attached to the generated GitHub
release — exactly what the [in-app updater](#in-app-updates) consumes.
Without a release tag, CI keeps the Gradle version defaults (0.4.0 /
versionCode 4) and still uses the UTC build date; a local build that omits
`-PapkDate` uses the local date.

The plain `v*` tags that belong to the Termux package releases
(`v0.40.0-termux.3`, …) do not trigger this workflow, so the two release
trains cannot collide.

---

## Installing and updating

Download `tanoshi-mobile-server-release-<yyyy-MM-dd>.apk` from the
[latest release](https://github.com/spainclaro-debug/tanoshi-termux/releases/latest)
(or a CI artifact), or build it yourself. Then either:

```bash
adb install -r android/app/build/outputs/apk/debug/tanoshi-mobile-server-release-*.apk
```

or copy the APK to the phone, open it, and allow "install unknown apps" for
the file manager/browser when Android asks.

The APK name carries the build date: CI uses the UTC build date, local builds
use the local date. Releases from 0.3.0 onward use
`tanoshi-mobile-server-release-<date>.apk`; the 0.1.0 and 0.2.0 release assets
were called `app-debug.apk` and are still valid downloads.

The app is **arm64-v8a only**. It will not install on 32-bit-only or x86
devices; the package manager reports a missing native library / incompatible
ABI.

### Updating

- **In-app:** once a release newer than the installed version is published,
  `Settings → Updates` offers **Download update**, then **Install update**. The
  app downloads the APK from the GitHub release and hands it to Android's
  package installer; the system asks you to confirm. The install stops the app
  (and the server), so reopen Tanoshi and start the server afterwards. See
  [In-app updates](#in-app-updates).
- **Manual:** download `tanoshi-mobile-server-release-<date>.apk` from the
  latest release and install it over the app, or use
  `adb install -r <downloaded.apk>`. Android accepts the update only because
  every release is signed with the shared key ([Signing](#signing)).
- **From 0.1.0:** 0.1.0 was signed with a throwaway CI debug key, so 0.2.0 or
  later will **not** install over it ("app not installed" / signature
  mismatch). Uninstall 0.1.0 once before installing a current release.
  Uninstalling deletes the app's private data (library, accounts, downloads)
  — copy `files/tanoshi/` out first if you need it (see
  [Storage layout](#storage-layout)). This is a one-time step: every later
  release shares the committed signing key and updates in place.

---

## First run

1. **Start the server** — open the app and tap **Start**. A foreground-service
   notification appears ("Running on port 8080") and the status card switches
   to `RUNNING` once the server answers on `http://127.0.0.1:8080/`.
2. **Create the admin account** — the app creates no default credentials.
   When the server reports that no account exists, a first-run dialog asks for
   a username (default `admin`, at least 3 characters) and a password (at
   least 8 characters) and registers the first account through the server's
   GraphQL `register` mutation with `isAdmin: true`.
   You can skip the dialog and register in the web UI instead, but do that
   immediately: until an account exists, anyone who can reach the port could
   claim the first (administrator) account.
3. **Open the web UI** — tap **Open in app** for the embedded WebView or
   **Open in browser** to use your normal browser at
   `http://127.0.0.1:<port>`. Log in with the account from step 2.
   To open it on another device on the same Wi-Fi, enable **Expose on LAN** in
   Settings, restart the server, then scan the
   [Share on your network QR code](#sharing-on-your-network-qr) from the
   Server screen.
4. **Install sources / configure** in the web UI like any other Tanoshi
   server. The nine bundled extensions are already present.

The server keeps running while the app is in the background, held by the
foreground service and a partial wake lock, until you tap **Stop** (in the app
or in the notification). Starting/stopping the server is always explicit: the
service does not silently resume after a reboot.

---

## Using the app

The app has three screens plus an embedded browser:

| Screen | What it does |
| --- | --- |
| **Server** | Server state (`STOPPED` / `STARTING` / `RUNNING` / `STOPPING` / `ERROR`), PID, port, bind address (`127.0.0.1` or `0.0.0.0`), uptime, Start/Stop, the local URL, **Open in browser**, **Open in app**, and (when LAN-exposed) the **Share on your network** QR card. The first-run admin dialog appears here. |
| **Logs** | Live server stdout/stderr with timestamps and stream colouring; auto-scroll toggle; copy-all and clear. `server.log` in the data directory mirrors the same output. |
| **Settings** | Appearance (themes), port, "Expose on LAN", raw `config.yml` editor, notification/battery permissions, in-app updates, and the storage paths. |
| **Browser** | WebView pointed at the local server (JavaScript and DOM storage enabled) with Back and Reload buttons; the catalogue auto-loads the next page as you scroll. The system back button exits the WebView before leaving the app. |

**Default port: 8080.** The advertised URL uses `127.0.0.1`; when LAN exposure
is on, other devices on the same network can use the phone's IP address
instead.

The notification has a **Stop server** action, so you can stop the server
without reopening the app. If you deny the notification permission on
Android 13+, the foreground service still runs but its notification is not
shown — keep the app icon/battery settings in mind when troubleshooting.

The **Settings** screen also holds the app-only [Appearance](#themes) theme
picker and the [Updates](#in-app-updates) section; both are described below.

---

## Sharing on your network (QR)

When the server is **running** and bound to `0.0.0.0` — i.e. **Expose on LAN**
is enabled in Settings *and* the server was restarted after enabling it — the
Server screen shows a **Share on your network** card:

- a QR code encoding `http://<host-ipv4>:<port>`, generated with ZXing (pure
  Java, no native code) off the main thread;
- the host `ip:port` and the full URL below it, with a copy button;
- a warning that this only works on the same Wi-Fi/Ethernet network and that
  the web UI uses plain HTTP.

Scanning the code with another device on the same network opens the Tanoshi
web UI directly in that device's browser. The app uses the first IPv4 address
of the active network when `ConnectivityManager` reports it as Wi-Fi or
Ethernet, and otherwise falls back to enumerating network interfaces —
always skipping loopback and link-local (`169.254.x.x`) addresses. The address
is re-resolved whenever the screen resumes, so it follows Wi-Fi changes.

If the card cannot show a QR code it explains what is missing instead: enable
Expose on LAN, start the server, restart the server to apply a bind-address
change, or connect to Wi-Fi/Ethernet. The card is informational — it never
changes the bind address itself.

**Security:** the other device reaches this server over your LAN in plain HTTP,
so anyone on that network who knows the address can reach the login page. Only
share on a network you trust, keep a strong admin password, and turn Expose on
LAN off when you don't need it — see [Security notes](#security-notes).

---

## The embedded web UI

The Tanoshi web UI (the same UI the Termux package serves) is compiled into
the server binary: the wasm bundle is embedded into `libtanoshi.so`, which
serves it over the loopback HTTP port. The app's **Open in app** screen is a
`WebView` pointed at that URL, and **Open in browser** hands the URL to your
browser. Both show exactly the same UI.

**Catalogue infinite scroll (0.2.0).** The embedded UI was rebuilt from the
package-3 web dist, which includes `patches/tanoshi-infinite-scroll.patch`. A
sentinel below the catalogue grid (`catalogue-scroll-sentinel`) is watched with
an `IntersectionObserver`: when it comes within about 600 px of the viewport,
the next page is fetched automatically, so normally you never have to press
anything. The **Load More** button stays as a manual fallback and changes to
*No more results* once a source runs out of pages.

Because the UI lives inside the server binary, **changing web-UI behaviour
means rebuilding `libtanoshi.so`** (see [`native/README.md`](native/README.md))
and shipping a new APK — rebuilding the Android app alone is not enough. The
web UI also keeps its own Tanoshi theme; the app's [themes](#themes) affect
only the Android UI around it.

## Themes

`Settings → Appearance` selects the Material 3 colour scheme for the app UI:

| Theme | Notes |
| --- | --- |
| **Dark** (default) | Near-black blue-grey surfaces with the amber Tanoshi accent. |
| **Light** | Warm paper surfaces with a darker amber primary; not an inverted dark scheme. |
| **Nord** | Polar Night backgrounds, Snow Storm text and Frost/Aurora accents. |
| **Catppuccin** | Mocha palette: base/mantle/crust surfaces with mauve, blue and peach accents. |

The choice applies immediately, is persisted in `AppSettings.themeId`
(unknown ids fall back to Dark) and also switches the system-bar icon
contrast. It affects the Android UI only — the embedded Tanoshi web UI keeps
its own theme.

## In-app updates

`Settings → Updates` shows the installed version and a **Check for updates**
button. Tapping it asks this repository's GitHub API for releases, considers
only published (non-draft, non-prerelease) tags starting with `android-v`, and
compares them numerically with the installed version (debug builds carry a
`-debug` version suffix such as `0.4.0-debug`; the updater strips it before
comparing).

When a newer release exists the section shows its version, tag, a release-notes
excerpt and the download size, with a **Download update** button (progress
bar). After the APK is downloaded, **Install update** hands it to Android's
`PackageInstaller`; the system then shows its own confirmation dialog and
replaces the app.

Requirements and limitations:

- **Network.** The check uses `https://api.github.com` without a token; if
  GitHub's rate limit is hit the app shows a message and you can try again
  later.
- **Install permission.** Android requires the "Install unknown apps"
  permission for Tanoshi (`REQUEST_INSTALL_PACKAGES` is declared; the user
  grants it). Without it, the section shows a warning and an **Allow app
  installs** button that opens the system setting.
- **Same signing key.** Updates install only because every release is signed
  with the shared key — see [Signing](#signing). An APK signed with another
  key is rejected by the system.
- **Download integrity.** The download is written to a temporary file, its
  size is checked, and the SHA-256 is verified when the GitHub release asset
  exposes a digest. Downloads land in the app cache and stale ones are cleared
  first.
- **Asset naming.** 0.3.0 releases publish
  `tanoshi-mobile-server-release-<date>.apk`; the updater prefers asset names
  starting with `tanoshi-mobile-server`, accepts any other `.apk` asset as a
  fallback, and still understands the legacy `app-debug.apk` name used by
  0.1.0/0.2.0 releases.
- **The system installs it.** Tanoshi never installs anything silently: you
  approve each install in the system dialog, and Android stops the app (and
  therefore the server) while it is replaced — reopen Tanoshi and start the
  server afterwards.
- **Manual only.** There is no background/automatic update check, and no
  delta or incremental update: each update is a full APK download.
- **Channel.** Only GitHub releases of
  [`spainclaro-debug/tanoshi-termux`](https://github.com/spainclaro-debug/tanoshi-termux/releases)
  tagged `android-v*` are considered; there is no Play Store channel.
- **From 0.1.0** the updater cannot help, because 0.1.0 predates it and is
  signed with a different key — see the one-time uninstall note in
  [Installing and updating](#installing-and-updating).

---

## Architecture

```
┌──────────────────────────────┐
│ Compose UI (MainActivity)    │
│  Server / Logs / Settings    │
│  WebView ────────────────────┼── HTTP ──┐
└──────────────┬───────────────┘          │
               │ start / stop             │ 127.0.0.1:<port> (default)
┌──────────────▼───────────────┐          │
│ TanhoshiService              │          │
│  foreground service          │          │
│ mediaPlayback|specialUse     │          │
│  wake lock + silent audio    │          │
│  ProcessBuilder              │          │
└──────────────┬───────────────┘          │
               │ exec                     │
┌──────────────▼───────────────┐    ┌─────▼──────────────────────┐
│ nativeLibraryDir/            │    │ Tanoshi HTTP server        │
│   libtanoshi.so  (server)    │    │ (child process)            │
│   libarchive.so              │    │ data: filesDir/tanoshi/    │
│   lib<plugin>.so  (plugins)  │    └────────────────────────────┘
└──────────────────────────────┘
```

### The server process

`TanhoshiService` runs in the app's own process (no binder plumbing needed)
and starts the server with `ProcessBuilder`:

- **Binary:** `<nativeLibraryDir>/libtanoshi.so` — see
  [Why `lib*.so` and `nativeLibraryDir`](#why-libso-and-nativelibrarydir).
- **Working directory:** the data home (`filesDir/tanoshi/`).
- **Environment:**

  | Variable | Value | Why |
  | --- | --- | --- |
  | `TANOSHI_HOME` | absolute `filesDir/tanoshi` | Tanoshi's data directory. |
  | `HOME` | absolute `filesDir` | Keeps any home-relative default inside the sandbox. |
  | `TANOSHI_LOG` | `info` | Server log level. |
  | `TANOSHI_BIND` | `127.0.0.1` (default) or `0.0.0.0` when LAN exposure is on | Listen address; upstream hardcodes `0.0.0.0`, the Android build reads this variable instead. |
  | `LD_LIBRARY_PATH` | `<nativeLibraryDir>` plus the system defaults | Lets the dynamic linker find `libarchive.so` next to the server. |
  | `TMPDIR` | the app's `cacheDir` | Scratch files stay in the app sandbox. |
  | `RUST_BACKTRACE` | `1` | Useful crash output in the log. |

- The service performs a readiness probe against
  `http://127.0.0.1:<port>/` and only then reports `RUNNING` (with a timeout
  and a process-death check).
- Stop sends `SIGTERM`, waits, then force-kills if needed. The service is
  `START_STICKY` and only restarts the server if the user had asked it to run.

### Why `lib*.so` and `nativeLibraryDir`

Android 10+ enforces W^X and SELinux rules: an app may no longer execute or
`dlopen` code written into its own writable data directory. The one executable
location the app owns is the installer-managed native library directory
(`nativeLibraryDir`). Therefore:

- the server binary is **shipped as `libtanoshi.so`** (and the plugins as
  `lib<name>.so`), so the package manager extracts them to
  `nativeLibraryDir/` during installation;
- `android:extractNativeLibs="true"` in `AndroidManifest.xml` and
  `packaging.jniLibs.useLegacyPackaging = true` in `app/build.gradle.kts` are
  required — without them libraries stay compressed inside the APK and are
  never extracted to a path that can be executed;
- the app never copies the server binary to `filesDir`.

The native libraries are linked 16 KB-page aligned (`-Wl,-z,max-page-size=16384`)
so they load on Android 15+ devices that use 16 KB memory pages. Rebuilds
must keep that flag — see [`native/README.md`](native/README.md).

### Plugins

Plugins are loaded in place from `nativeLibraryDir` by the patched server; the
Android patch skips the staging copy into the writable plugin directory that
the upstream code would perform (that copy exists only to rename plugins, and
Android would refuse to load it anyway).

The `plugin_path` in `config.yml` still points at the writable
`filesDir/tanoshi/plugins/` directory, which the plugins use for their
preferences. **Plugins are bundled with the APK and cannot be updated from
inside the app**: installing or updating a source means shipping a new APK
built with the matching plugin ABI (`tanoshi-lib` 0.38.0 for Tanoshi 0.40.0).

### `libarchive`

Tanoshi uses `libarchive` to read chapter archives. The APK bundles
**libarchive 3.8.11**, built minimally: zlib is linked **statically into the
library** (so there is no separate `libz.so` runtime dependency), and OpenSSL,
libxml2 and iconv are disabled. The only dynamic dependencies left are libc
and libdl, and its SONAME is patched to plain `libarchive.so` so the server's
`LD_LIBRARY_PATH` lookup finds it.

### Foreground service and battery

- The service is declared as **`specialUse|mediaPlayback`**:
  - `specialUse` (with `FOREGROUND_SERVICE_SPECIAL_USE` and a
    `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` description) describes the
    self-hosted-server use case. The obvious alternative, `dataSync`, is
    capped at **6 hours per day** on Android 15+, which would stop a
    self-hosted server.
  - `mediaPlayback` (with `FOREGROUND_SERVICE_MEDIA_PLAYBACK`) is used
    together with a hidden silent-audio loop so the app is treated as playing
    media while the server runs — see
    [Keeping the server alive in the background](#keeping-the-server-alive-in-the-background).
  - At runtime the app passes `specialUse|mediaPlayback` on Android 14+
    (API 34+), `mediaPlayback` on Android 10–13 (API 29–33; the
    `specialUse` type only exists on 14+) and no type on older releases, which
    predate foreground-service types.
- A **partial wake lock** keeps the process alive while the screen is off; it
  is released whenever the server stops.
- The silent keep-alive audio adds a small, continuous battery cost while the
  server runs; it is inaudible and creates no extra notification.
- `POST_NOTIFICATIONS` is requested on Android 13+ (API 33+); the app also
  offers an "ignore battery optimisations" shortcut, because many OEM builds
  kill long-running background apps aggressively.
- If the server process is killed unexpectedly, a watchdog restarts it
  automatically (bounded) — see the next section.
- The server does not auto-start at boot. You start it from the app.

---

## Keeping the server alive in the background

Android 12+ and many OEM ROMs are aggressive about killing background apps and
their **child processes** — the bundled `libtanoshi.so` server runs as a child
of the app process, so it is a target. The app ships two best-effort
mitigations while the server is running; the most reliable fixes for the
Android 12+ phantom-process killer are device-side settings (below). None of
this is a guarantee: a force-stop, an OEM "deep sleep" policy or the
phantom-process killer can still stop the server.

### Hidden silent-audio keep-alive

While the server runs, `TanhoshiService` loops a **generated one-second silent
WAV** through `MediaPlayer` and keeps the foreground service in the
`mediaPlayback` (plus `specialUse`) state:

- The WAV is generated at runtime into the app cache
  (`cacheDir/keepalive/silence.wav`): 44.1 kHz, 16-bit mono PCM silence,
  about 88 KB, reused between starts. Nothing is bundled with the APK.
- Playback uses media audio attributes, looping, **volume forced to 0**
  (inaudible even at maximum media volume) and a partial wake mode. There is
  **no MediaSession, no media-style notification, no audio-focus request and no
  extra UI** — the only notification remains the normal "Tanoshi server"
  foreground notification.
- It starts when the server starts and stops when the server stops (or fails,
  or the service is destroyed). **There is no setting or toggle**: stopping
  the server is the only way to stop it.
- Cost: a continuously looping silent audio stream (plus the wake lock) keeps
  the CPU awake, so expect a small, continuous battery drain while the server
  runs. It is silent and does not use the speaker.

`Settings → Permissions → Ignore battery optimisations` remains recommended on
top of this.

### Auto-restart watchdog

If the server process exits **unexpectedly** while you still want it running,
the service keeps the foreground notification, wake lock and silent audio
alive and restarts the server:

- at most **5 automatic restarts in a rolling 10-minute window**, about **3
  seconds** apart;
- the dashboard shows `STARTING` during the restart, and the Logs tab records
  lines such as `The Tanoshi server process was killed; restarting it
  automatically (attempt 1/5, in 3 s)`;
- a user-requested Stop (or Restart) cancels the pending restart;
- when the budget is exhausted the app records `giving up automatic restarts
  (5/5 attempts within 10 min)`, switches to `ERROR`, releases the wake lock
  and silent audio and stops the foreground service — the same terminal state
  as before 0.4.0.

The budget is rolling: after ten minutes without a restart the app can restart
the server automatically again.

### If the Android phantom-process killer is killing the server

Android 12 introduced the **phantom-process killer**. It limits app *child*
processes device-wide: when more than **32 phantom processes** exist on the
device, or when a child process uses excessive CPU, `ActivityManager` kills
those child processes. The Tanoshi server (plus its extension worker
processes) is exactly such a child; the app itself may survive, so the server
can disappear while the UI still works. The silent-audio keep-alive improves
the app's importance and how OEMs treat it, but it **does not exempt the app
from the phantom-process limits**.

How to tell it is the cause:

- The Logs tab shows `Tanoshi server process <pid> exited (code ...)`, then
  either the watchdog restart lines or (after 5 attempts) the `ERROR` state.
- From a computer with USB debugging:

  ```bash
  adb logcat -s ActivityManager
  ```

  Look for `Killing PhantomProcessRecord`, `Trimming phantom processes` or an
  `excessive cpu` reason. Other OEM task killers may log different messages.

Recommended workarounds (device-side, no root; **not required for everyone**):

- **Developer options (no ADB):** enable Developer options and look for
  **"Disable child process restrictions"** (Samsung/One UI and some other
  builds expose this toggle). Enable it, then restart the server.
- **ADB, Android 12 (API 31):**

  ```bash
  adb shell settings put global settings_enable_monitor_phantom_procs false
  adb shell settings get global settings_enable_monitor_phantom_procs  # -> false
  ```

- **ADB, Android 12L/13/14/15 (API 32+):**

  ```bash
  adb shell device_config put activity_manager max_phantom_processes 2147483647
  adb shell device_config get activity_manager max_phantom_processes   # -> 2147483647
  ```

  On those builds the `settings` flag may not exist or may not be honoured;
  the `device_config` value is the one the killer uses. A reboot may be needed
  before the change takes effect, and an OS update can reset it (re-check and
  re-apply afterwards).

Not every device or OEM build exposes these settings or honours them; the
commands can be ignored, restricted or reset by a system update. Trade-offs:
these are **device-wide** settings, not app settings — disabling the
phantom-process monitor affects every app, can increase background
activity/memory use and battery drain, and removes a protection Android added
deliberately. Only do it if you actually see the killer in `logcat`; on most
phones the silent audio, watchdog and battery-optimisation exemption are
enough. The app cannot change these settings itself.

If the server still dies, check the OEM's battery/"deep sleep"/autostart
settings and keep the app exempted from battery optimisation.

---

## Storage layout

Everything lives in the app's private files directory. For the debug build
the package is `dev.spainclaro.tanoshi.debug`; the release build uses
`dev.spainclaro.tanoshi`.

```
/data/data/<package>/files/tanoshi/      # TANOSHI_HOME
├── config.yml         # port, secret, absolute paths, extension timeouts
├── tanoshi.db         # SQLite: accounts, library, history
├── server.log         # mirrored stdout/stderr (rotates to server.log.1 at ~5 MB)
├── downloads/         # downloaded chapters
├── cache/             # image cache
├── manga/             # local ("Local source") folder
└── plugins/           # extension preferences only
```

The actual native libraries (including plugins) are **not** here — they are
in `nativeLibraryDir` (`/data/app/.../lib/arm64/libtanoshi.so`, …), managed
by the package manager.

Notes:

- The app has `android:allowBackup="false"`, so Android does not back this
  data up. **Uninstalling deletes it** (library, accounts, downloads).
- Downloaded update APKs are kept in the app cache (`cacheDir/updates/`),
  outside `files/tanoshi/`; Android may clear them at any time.
- To move data, copy the `tanoshi/` directory out of a debug build with
  `adb exec-out run-as dev.spainclaro.tanoshi.debug tar -C files -cf - tanoshi > tanoshi-data.tar`
  and push it back into a new install the same way.
- Nothing is written to shared/external storage.

---

## Settings and `config.yml`

The Settings screen controls the Android wrapper and the server's config:

- **Appearance** — the four app themes ([Themes](#themes)).
- **Updates** — the in-app updater ([In-app updates](#in-app-updates)).
- **Port** — 1024–65535 (Android does not allow unprivileged apps to bind
  below 1024). The value is written to `config.yml` and remembered; the
  server must be restarted to listen on the new port. Saving the port (or the
  `config.yml`) works even before the server has ever been started: since
  0.3.0 the app creates the data directory as part of the save.
- **Expose on LAN** — when on, the next start uses `TANOSHI_BIND=0.0.0.0`, so
  other devices can reach `http://<phone-ip>:<port>`. The app warns that this
  is plain HTTP with no TLS. The default is **off** (loopback only). Once it is
  on and the server is running, the Server screen also shows the
  [Share on your network QR card](#sharing-on-your-network-qr).
- **`config.yml` editor** — shows the file as text, validates it on save and
  writes it atomically. Keep all paths **absolute** and inside the app's
  private directory; **`secret` must stay exactly 16 characters** because it
  is used as an AES-128 key. Changing the secret invalidates existing login
  tokens (everyone must log in again). Restart the server after editing.
- **Notifications** — requests `POST_NOTIFICATIONS` on Android 13+.
- **Ignore battery optimisations** — asks Android to exempt the app from
  battery optimisation (particularly relevant on OEM ROMs).
- The screen also shows the data directory path so you can find
  `server.log`, `config.yml` and `tanoshi.db`.

The config file is generated on first start with Tanoshi's current schema and
the app's private paths. The Termux package's `data/config.yml` from this
repository's root README is interchangeable in format, but paths must be
rewritten for the app sandbox — in practice, don't copy it across; let the
app generate it.

---

## Native binaries and rebuilding them

The APK embeds these files in
`app/src/main/jniLibs/arm64-v8a/`:

| File | Source |
| --- | --- |
| `libtanoshi.so` | Tanoshi v0.40.0 server (renamed from `tanoshi`) |
| `libarchive.so` | libarchive 3.8.11, minimal build with zlib linked statically (only libc/libdl dependencies, SONAME `libarchive.so`) |
| `libmangadex.so`, `libguya.so`, `libmanhwa18cc.so`, `libweebcentral.so`, `libatsumaru.so`, `libqiscans.so`, `libmangaball.so`, `libmadaradex.so`, `libhentainexus.so` | The nine extension plugins |

They are prebuilt and committed; a Gradle build does not compile Rust. To
update Tanoshi or a plugin, run the pipeline documented in
[`native/README.md`](native/README.md):

- [`native/build_tanoshi.sh`](native/build_tanoshi.sh) — clone upstream,
  apply `patches/` + `native/patches/0001-android-app.patch`, embed the
  vendored [`native/web-dist/`](native/web-dist) (the package-3 infinite-scroll
  web UI) when the source tree has no `crates/tanoshi-web/dist`, and build the
  `aarch64-linux-android` server;
- [`native/build_libarchive.sh`](native/build_libarchive.sh) — cross-build the
  minimal `libarchive.so` with the NDK;
- [`native/sync_prebuilt.sh`](native/sync_prebuilt.sh) — copy the outputs into
  `jniLibs/arm64-v8a/` with the required `lib` prefix and verify them.

The root-of-repository Termux packaging (`tanoshi-termux-aarch64.tar.gz`,
`patches/`, `vendor/compress-tools-0.16.1/`, `lib/build.sh`) remains the
source of the bundled server binary and plugins: the Android pipeline reuses
its patch set, vendored crate and prebuilt extension plugins, and rebuilds
the server with the Android-app patch. Checked-in binaries can be replaced by
re-running the scripts and committing `jniLibs/arm64-v8a/` — no Kotlin or
Gradle change is needed.
Bit-for-bit reproducibility is not guaranteed (moving upstream extension
refs, toolchain versions, embedded build paths); see the "Reproducibility
caveats" section of the native guide.

---

## Security notes

- **HTTP only, no TLS.** Everything, including your password and session
  tokens, travels unencrypted. This is acceptable for loopback, not for a
  network you don't trust.
- **Loopback by default.** The server binds `127.0.0.1` unless you enable
  "Expose on LAN". Prefer a VPN (WireGuard, Tailscale) over LAN exposure for
  remote access; a reverse proxy with TLS is the other option.
- **LAN exposure is a login surface.** When enabled, any device on the same
  network can reach the login page over plain HTTP — the
  [QR share card](#sharing-on-your-network-qr) just makes that URL easier to
  open. Use a strong password and only turn it on when needed.
- **No default account.** No `admin`/`tanoshi123`-style credentials are baked
  in. Register the administrator immediately on first run; before the first
  account exists, whoever reaches the port could register it.
- **`config.yml` contains the JWT signing secret.** It lives in app-private
  storage; don't copy it into issues/logs. Rotating it logs everyone out.
- **Cleartext network policy.** The app's
  `network_security_config.xml` permits cleartext HTTP only for `127.0.0.1`,
  `localhost` and `10.0.2.2` (emulator loopback); WebView/browser traffic to
  the local server works, and cleartext to other hosts stays blocked.
- **Third-party content.** The extensions scrape third-party sites; respect
  their terms of service and your local laws.
- **Updates come from GitHub.** The updater contacts `api.github.com` and
  downloads release assets from this repository only. It never installs
  anything without the system confirmation dialog, and Android only accepts an
  APK signed with the same key as the installed app. Do not sideload APKs from
  other sources: they will not share this key or this app's data.
- **Shared signing key.** The committed keystore is for stable sideload
  updates, not secrecy. It is not a Play Store signing key; a Play Store
  release would need Play App Signing, a privacy policy and the `specialUse`
  foreground-service declaration reviewed — see the open questions document.

---

## Troubleshooting

Open the **Logs** screen first; the same output is appended to
`filesDir/tanoshi/server.log`, which rotates to `server.log.1` at ~5 MB.
Clearing the Logs screen clears the in-memory view only.

**"Native library missing" / the server cannot be started.** The APK was built
without `jniLibs` or for the wrong ABI. Confirm the APK contains all eleven
libraries (the server, libarchive and the nine plugins) and that the device is
arm64:

```bash
unzip -l android/app/build/outputs/apk/debug/tanoshi-mobile-server-release-*.apk \
  | grep 'lib/arm64-v8a'
adb shell getprop ro.product.cpu.abi     # expect arm64-v8a
```

Reinstall a correct build; do not copy `libtanoshi.so` into app storage —
Android will refuse to execute it there.

**Port already in use.** Another app (Termux, a previous Tanoshi, a proxy) is
on port 8080. Change the port in Settings or the `config.yml` editor, stop and
start the server. Keep it ≥ 1024.

**Saving the port or `config.yml` fails with `config.yml.tmp: open failed:
ENOENT`.** This was a 0.2.0 and earlier bug when the server had never been
started (the data directory did not exist yet). 0.3.0 creates the data
directory and the parent of `config.yml` before writing, so update to 0.3.0 or
later if you hit it.

**Server starts and immediately stops / `ERROR` with an exit code.** Check the
logs. Common causes: a `config.yml` with an invalid `secret` (the app repairs
non-16-character secrets on start), an unwritable data directory, or a crashed
plugin build. Restarting the app regenerates missing directories; deleting
`config.yml` makes the app generate a fresh one.

**Plugins do not appear in the web UI.** The bundled plugins and
`libtanoshi.so` disagree on the `tanoshi-lib` ABI (0.38.0 for Tanoshi 0.40.0).
They ship together in one APK, so this usually means a mismatched manual
rebuild — rebuild server + plugins together.

**Cannot reach the server from another device on the LAN.** Enable "Expose on
LAN", restart, and check the status card shows `0.0.0.0`. Make sure both
devices are on the same network (guest Wi-Fi / AP isolation blocks this), and
use `http://<phone-ip>:<port>` — or scan the
[Share on your network QR code](#sharing-on-your-network-qr).

**The Share on your network card shows no QR code.** It needs all three: the
server running, bound to `0.0.0.0` (Expose on LAN enabled *and* the server
restarted afterwards), and a discoverable Wi-Fi/Ethernet IPv4 address. The
card's text says which one is missing — connect to Wi-Fi/Ethernet, start or
restart the server, or enable Expose on LAN. Guest Wi-Fi / client-isolation
networks will not let the other device reach the phone even when a QR code is
shown.

**The shared QR code does not scan or the other device cannot open the URL.**
Use a non-guest Wi-Fi/Ethernet network that allows client-to-client traffic,
raise the screen brightness, and make sure the phone's hotspot/client
isolation is not blocking the connection. The QR encodes a plain
`http://<ipv4>:<port>` URL; a device on a different network (mobile data,
another VLAN) cannot reach it.

**"Check for updates" fails.** The app queries GitHub's public API without a
token. Temporary network problems and GitHub API rate limits both fail this
way — wait and retry. A release is only offered when it is a published
(non-draft, non-prerelease) `android-v*` tag with an `.apk` asset.

**"Install update" says Android does not allow the app to install updates.**
Grant "Install unknown apps" for Tanoshi (the **Allow app installs** button in
the Updates section opens the right system screen), then press **Install
update** again.

**An update downloads but Android refuses to install it ("App not installed").**
Most likely the installed app is signed with a different key — this is
expected for 0.1.0 builds, which must be uninstalled once. It can also mean
the wrong app variant/application id is installed, not enough free storage, or
a corrupted download (delete the update and download it again). Every release
from this repository shares the same signing key.

**No update is offered although a release exists.** Check that the release is
tagged `android-vX.Y.Z` (not a Termux `v*` tag), is not a draft or
pre-release, has an `.apk` asset, and that its version is actually newer than
the installed one (a local debug build can already be at or above the release
version).

**The server is killed in the background.** Exempt the app from battery
optimisation (Settings → "Ignore battery optimisations"), then check the OEM's
"autostart"/"background app" settings. From 0.4.0 the app also loops an
inaudible silent-audio keep-alive and auto-restarts a killed server (up to 5
times in 10 minutes) — see
[Keeping the server alive in the background](#keeping-the-server-alive-in-the-background).
If it still happens, the Android 12+ *phantom-process killer* is the likely
cause: confirm with `adb logcat -s ActivityManager` (`Killing
PhantomProcessRecord`) and use the Developer-options/ADB workaround described
in that section. Keeping the notification visible helps.

**The server restarts itself (or shows ERROR after doing so).** This is the
0.4.0 auto-restart watchdog: an unexpected server-process exit triggers up to
5 automatic restarts in a rolling 10-minute window, roughly 3 s apart. The
Logs tab shows each attempt (`restarting it automatically (attempt N/5...)`).
After five attempts the server stays `ERROR` and stays stopped; check the log
for the exit code, then start it again or follow the phantom-killer guidance
above.

**Notification not shown on Android 13+.** Grant the notification permission
(Settings → Notifications). The server still runs when it is denied, but the
foreground-service notification and its Stop action are hidden.

**WebView is blank or the login page does not load.** Wait until the status
card says `RUNNING` (the WebView cannot load during startup); then use the
browser's back button/reload. If the server was restarted on a different
port, reopen the WebView from the Server screen.

**Login loop / "Unauthorized" after editing `config.yml`.** Changing
`secret` (or the app repairing a bad one) invalidates all sessions. Log in
again.

**"secret must be exactly 16 characters (AES-128 key)" when saving.**
Tanoshi encrypts cover URLs with AES-128-CBC and the key is the secret; use
exactly 16 characters.

**Crash or `dlopen` failure only on Android 15+ devices with 16 KB pages.** The
binaries were not linked 16 KB-page aligned. Rebuild with
`-Wl,-z,max-page-size=16384` as described in
[`native/README.md`](native/README.md) and reinstall.

**Uninstalling deletes my library.** Yes — the data is in app-private storage
and `allowBackup` is off. Copy the `tanoshi/` folder out first (see
[Storage layout](#storage-layout)).

---

## Versions

| Component | Version |
| --- | --- |
| Gradle wrapper | 8.9 |
| Android Gradle Plugin (AGP) | 8.7.3 |
| Kotlin | 2.0.21 |
| Compose BOM | 2024.12.01 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 (Android 8.0) |
| JDK | 17 |
| Tanoshi server | 0.40.0 (`VERSION`) |
| `tanoshi-lib` (plugin ABI) | 0.38.0 (`LIB_VERSION`) |
| libarchive | 3.8.11 (custom minimal build; `compress-tools` 0.16.1 requires ≥ 3.2.0) |
| Embedded web UI | Package-3 wasm dist (`native/web-dist/`), built with the infinite-scroll patch |
| QR sharing | ZXing core 3.5.3 (pure Java) |
| Foreground service types | `specialUse` + `mediaPlayback` (silent keep-alive; from 0.4.0) |
| App version | 0.4.0 (`versionName`); `versionCode` 4 in local builds, 400 for the `android-v0.4.0` tag |
| APK filename | `tanoshi-mobile-server-release-<yyyy-MM-dd>.apk` (0.3.0+; 0.1.0/0.2.0 used `app-debug.apk`) |
| Signing key | `android/keystore/tanoshi-signing.jks` (alias `tanoshi`), shared by all releases |
| Android NDK (native rebuilds only) | r26+ |

The Termux package and the APK ship the same Tanoshi and extension versions;
when bumping one, bump `VERSION` / `LIB_VERSION` and rebuild both.

---

## Project layout

```
android/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/dev/spainclaro/tanoshi/   # app, service, admin bootstrap, updater, Compose UI
│       ├── res/                           # strings, themes, icons, network policy
│       └── jniLibs/arm64-v8a/             # libtanoshi.so, libarchive.so, lib<plugin>.so
├── gradle/wrapper/                        # Gradle 8.9 wrapper
├── keystore/                              # shared sideload signing key + README
├── native/                                # native pipeline + vendored web-dist
├── build.gradle.kts
├── gradle.properties
├── README.md                              # this file
├── CHANGELOG.md
├── ASSUMPTIONS_AND_OPEN_QUESTIONS.md
├── gradlew
└── settings.gradle.kts
```

The repository root keeps the Termux packaging (`install.sh`, `lib/`,
`patches/`, `vendor/`, `extensions/`, the release tarball) and the CI
workflows live in `.github/workflows/`.

---

## Changelog

Release history lives in [`CHANGELOG.md`](CHANGELOG.md), and published APKs are
on the [latest release](https://github.com/spainclaro-debug/tanoshi-termux/releases/latest).

---

## Credits and licences

- Tanoshi © luigi311, MIT — <https://github.com/luigi311/tanoshi>
- Extensions © luigi311 — <https://github.com/luigi311/tanoshi-extensions>
- The five ported extensions derive from
  [Keiyoushi](https://github.com/keiyoushi/extensions) (Apache-2.0) and are
  adapted to Tanoshi's plugin API; their sources are in `extensions/` at the
  repository root.
- Termux/Android patches: `patches/` (Termux package) and
  `native/patches/0001-android-app.patch` (this app), documented in
  `patches/README-patches.txt` and [`native/README.md`](native/README.md).
- See [`ASSUMPTIONS_AND_OPEN_QUESTIONS.md`](ASSUMPTIONS_AND_OPEN_QUESTIONS.md)
  for design decisions that still need the repository owner's confirmation.
