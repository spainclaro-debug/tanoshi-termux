# Tanoshi for Android (standalone APK)

A standalone Android app that bundles the same [Tanoshi](https://github.com/luigi311/tanoshi)
manga server and the same nine extensions as the Termux package in the
repository root — **without Termux**. The app runs the server as a foreground
service and shows its web UI in an embedded WebView (or your normal browser).

This directory is the Android Gradle project (`dev.spainclaro.tanoshi`).
The `arm64-v8a` native binaries are prebuilt and checked in, so building the
APK only needs JDK 17 and the Android SDK — no Rust or NDK. See
[`native/README.md`](native/README.md) to rebuild the native pieces.

> **Sideload only, arm64-v8a only, HTTP only.** There is no Play Store build,
> no TLS and no default account. Read the [Security notes](#security-notes)
> before exposing it on a LAN.

---

## Contents

- [What you get](#what-you-get)
- [Requirements](#requirements)
- [Building the APK](#building-the-apk)
- [Installing (sideloading)](#installing-sideloading)
- [First run](#first-run)
- [Using the app](#using-the-app)
- [Architecture](#architecture)
- [Storage layout](#storage-layout)
- [Settings and `config.yml`](#settings-and-configyml)
- [Native binaries and rebuilding them](#native-binaries-and-rebuilding-them)
- [Security notes](#security-notes)
- [Troubleshooting](#troubleshooting)
- [Versions](#versions)
- [Project layout](#project-layout)
- [Credits and licences](#credits-and-licences)

---

## What you get

- The Tanoshi **v0.40.0** server, built from upstream `luigi311/tanoshi` with
  this repository's Termux patch set plus the Android-app patch, running as an
  `aarch64` native process inside the app's sandbox.
- **Nine extensions** bundled as native plugins: Mangadex, Guya, Manhwa18cc,
  WeebCentral, Atsumaru, QiScans, Manga Ball, MadaraDex and HentaiNexus.
- A small Material 3 wrapper: start/stop the server, watch its logs, open the
  web UI, edit the port, toggle LAN exposure and inspect/edit `config.yml`.
- The entire server data directory lives in the app's private storage; the
  phone needs no root, no Termux and no shell.

The APK is large because it carries the ~35 MB server binary plus `libarchive`
and the plugins. It is not split by ABI — only `arm64-v8a` is shipped.

---

## Requirements

**To run the app**

- Android **8.0 (API 26)** or newer, on an **arm64 / aarch64** device.
  (32-bit and x86/x86_64 builds are not bundled — see
  [`ASSUMPTIONS_AND_OPEN_QUESTIONS.md`](ASSUMPTIONS_AND_OPEN_QUESTIONS.md).)
- ~250 MB free space for the app and a fresh library.

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
`android/app/build/outputs/apk/debug/app-debug.apk`.

### Command line

```bash
cd android
./gradlew :app:assembleDebug
```

The first run downloads the Gradle 8.9 distribution and the Compose
dependencies; later builds are cached. Other useful tasks:

```bash
./gradlew :app:assembleRelease   # release variant (see Signing)
./gradlew :app:installDebug      # build + install on a connected device
./gradlew clean                  # remove build outputs
```

### Signing

- **Debug builds** are signed with the standard per-machine debug keystore
  (`~/.android/debug.keystore`, created automatically). CI publishes this
  variant. Its application id is `dev.spainclaro.tanoshi.debug`, so it can be
  installed alongside a release build, but they do not share data.
- **Release builds** currently reuse the debug signing configuration in
  `app/build.gradle.kts` as a placeholder for sideloading. For a properly
  signed release, configure your own keystore and **never commit it**:

  ```bash
  keytool -genkeypair -v -keystore tanoshi-release.jks \
    -alias tanoshi -keyalg RSA -keysize 4096 -validity 10000
  ```

  Then replace the placeholder signing configuration (for example with a
  `release` signing config that reads the store path/passwords from
  environment variables or CI secrets) and build with
  `./gradlew :app:assembleRelease`.

  Keep this keystore safe: Android refuses to update an installed app whose
  signature changed, and reinstalling from scratch deletes the app's data.

### CI

[`.github/workflows/android-apk.yml`](../.github/workflows/android-apk.yml)
builds `:app:assembleDebug` on every push, on pull requests that touch
`android/**` or the workflow, and on manual dispatch. It installs JDK 17
(Temurin) and the Android SDK packages `platforms;android-35` and
`build-tools;35.0.0` with `sdkmanager`, runs Gradle with the wrapper, and
uploads the APK as the **`tanoshi-android-debug-apk`** artifact. No third-party
Android setup/release actions are used: the SDK is provisioned with
`sdkmanager` and releases are published with the preinstalled GitHub CLI.

Android releases use tags of the form **`android-v*`** (for example
`android-v0.1.0`), and for those tags the same APK is attached to a new GitHub
release. The `v*` tags that belong to the Termux package releases
(`v0.40.0-termux.3`, …) do not trigger this workflow, so the two release trains
cannot collide.

---

## Installing (sideloading)

Download `app-debug.apk` from the CI artifact/release, or build it yourself.
Then either:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

or copy the APK to the phone, open it, and allow "install unknown apps" for
the file manager/browser when Android asks.

The app is **arm64-v8a only**. It will not install on 32-bit-only or x86
devices; the package manager reports a missing native library / incompatible
ABI.

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
| **Server** | Server state (`STOPPED` / `STARTING` / `RUNNING` / `STOPPING` / `ERROR`), PID, port, bind address (`127.0.0.1` or `0.0.0.0`), uptime, Start/Stop, the local URL, **Open in browser** and **Open in app**. The first-run admin dialog appears here. |
| **Logs** | Live server stdout/stderr with timestamps and stream colouring; auto-scroll toggle; copy-all and clear. `server.log` in the data directory mirrors the same output. |
| **Settings** | Port, "Expose on LAN", raw `config.yml` editor, notification permission, battery-optimisation exemption, and the data directory path. |
| **Browser** | WebView pointed at the local server (JavaScript and DOM storage enabled) with Back and Reload buttons; the system back button exits the WebView before leaving the app. |

**Default port: 8080.** The advertised URL uses `127.0.0.1`; when LAN exposure
is on, other devices on the same network can use the phone's IP address
instead.

The notification has a **Stop server** action, so you can stop the server
without reopening the app. If you deny the notification permission on
Android 13+, the foreground service still runs but its notification is not
shown — keep the app icon/battery settings in mind when troubleshooting.

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
│  (specialUse) + wake lock    │          │
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

- The service uses the **`specialUse`** foreground-service type with the
  `FOREGROUND_SERVICE_SPECIAL_USE` permission and a
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` description. The obvious alternative,
  `dataSync`, is capped at **6 hours per day** on Android 15+, which would
  stop a self-hosted server — hence `specialUse`.
- A **partial wake lock** keeps the process alive while the screen is off; it
  is released whenever the server stops.
- `POST_NOTIFICATIONS` is requested on Android 13+ (API 33+); the app also
  offers an "ignore battery optimisations" shortcut, because many OEM builds
  kill long-running background apps aggressively.
- The server does not auto-start at boot. You start it from the app.

---

## Storage layout

Everything lives in the app's private files directory. For the debug build
the package is `dev.spainclaro.tanoshi.debug`; the release build uses
`dev.spainclaro.tanoshi`.

```
/data/data/<package>/files/tanoshi/      # TANOSHI_HOME
├── config.yml         # port, secret, absolute paths, extension timeouts
├── tanoshi.db         # SQLite: accounts, library, history
├── server.log         # mirrored stdout/stderr (rotated at ~5 MB)
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
- To move data, copy the `tanoshi/` directory out of a debug build with
  `adb exec-out run-as dev.spainclaro.tanoshi.debug tar -C files -cf - tanoshi > tanoshi-data.tar`
  and push it back into a new install the same way.
- Nothing is written to shared/external storage.

---

## Settings and `config.yml`

The Settings screen controls the Android wrapper and the server's config:

- **Port** — 1024–65535 (Android does not allow unprivileged apps to bind
  below 1024). The value is written to `config.yml` and remembered; the
  server must be restarted to listen on the new port.
- **Expose on LAN** — when on, the next start uses `TANOSHI_BIND=0.0.0.0`, so
  other devices can reach `http://<phone-ip>:<port>`. The app warns that this
  is plain HTTP with no TLS. The default is **off** (loopback only).
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
  apply `patches/` + `native/patches/0001-android-app.patch`, build the wasm
  web UI and the `aarch64-linux-android` server;
- [`native/build_libarchive.sh`](native/build_libarchive.sh) — cross-build the
  minimal `libarchive.so` with the NDK;
- [`native/sync_prebuilt.sh`](native/sync_prebuilt.sh) — copy the outputs into
  `jniLibs/arm64-v8a/` with the required `lib` prefix and verify them.

The root-of-repository Termux packaging (`tanoshi-termux-aarch64.tar.gz`,
`patches/`, `vendor/compress-tools-0.16.1/`, `lib/build.sh`) remains the
source of the bundled server binary and plugins: the Android pipeline reuses
its patch set, vendored crate and prebuilt extension plugins, and rebuilds
the server with the Android-app patch. Checked-in binaries can be replaced by
re-running the scripts
and committing `jniLibs/arm64-v8a/` — no Kotlin or Gradle change is needed.
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
  network can reach the login page over plain HTTP. Use a strong password and
  only turn it on when needed.
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
- **Play Store.** This is a sideloaded build. A Play Store release would need
  the `specialUse` foreground-service declaration reviewed, a privacy policy
  and a proper release signing key — see the open questions document.

---

## Troubleshooting

Open the **Logs** screen first; the same output is in
`filesDir/tanoshi/server.log` (rotate/clear from the app, capped at ~5 MB).

**"Native library missing" / the server cannot be started.** The APK was built
without `jniLibs` or for the wrong ABI. Confirm the APK contains all eleven
libraries (the server, libarchive and the nine plugins) and that the device is
arm64:

```bash
unzip -l app-debug.apk | grep 'lib/arm64-v8a'
adb shell getprop ro.product.cpu.abi     # expect arm64-v8a
```

Reinstall a correct build; do not copy `libtanoshi.so` into app storage —
Android will refuse to execute it there.

**Port already in use.** Another app (Termux, a previous Tanoshi, a proxy) is
on port 8080. Change the port in Settings or the `config.yml` editor, stop and
start the server. Keep it ≥ 1024.

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
use `http://<phone-ip>:<port>`.

**The server is killed in the background.** Exempt the app from battery
optimisation (Settings → "Ignore battery optimisations"), then check the OEM's
"autostart"/"background app" settings. On Android 12+ the OS's *phantom
process killer* can still kill app child processes under memory pressure; a
foreground service makes this much less likely. Keeping the notification
visible helps.

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
| App version | 0.1.0 (`versionName`), `versionCode` 1 |
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
│       ├── java/dev/spainclaro/tanoshi/   # app, service, admin bootstrap, Compose UI
│       ├── res/                           # strings, themes, icons, network policy
│       └── jniLibs/arm64-v8a/             # libtanoshi.so, libarchive.so, lib<plugin>.so
├── gradle/wrapper/                        # Gradle 8.9 wrapper
├── native/                                # native rebuild pipeline (native/README.md)
├── build.gradle.kts
├── gradle.properties
├── README.md                              # this file
├── ASSUMPTIONS_AND_OPEN_QUESTIONS.md
├── gradlew
└── settings.gradle.kts
```

The repository root keeps the Termux packaging (`install.sh`, `lib/`,
`patches/`, `vendor/`, `extensions/`, the release tarball) and the CI
workflows live in `.github/workflows/`.

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
