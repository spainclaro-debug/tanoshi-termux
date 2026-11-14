# Tanoshi for Termux

A self-contained, ready-to-run build of [Tanoshi](https://github.com/luigi311/tanoshi)
— a self-hosted web manga reader — packaged for **Termux on Android (arm64)**.

Upstream ships no Android binary (only glibc Linux, Windows and macOS), so this
package contains a native `aarch64` Android build plus nine source extensions,
and the patch set needed to rebuild everything on Termux.

> This repository also ships a **standalone Android app** that runs the same
> server without Termux — see [Android app (no Termux)](#android-app-no-termux)
> below.

---

## Android app (no Termux)

[`android/`](android/README.md) contains a complete standalone Android app that
runs the same Tanoshi server **inside the APK** — no Termux, no shell, no
external runtime. It is the easiest way to run Tanoshi on a phone: install one
APK, tap **Start server**, and open the web UI.

The app bundles Tanoshi **0.40.0** and the nine source extensions (MangaDex,
Guya, WeebCentral, Atsumaru, QiScans, MangaBall, MadaraDex, Manhwa18cc,
HentaiNexus). The current release is **0.4.0**; download it from the
[latest release](https://github.com/spainclaro-debug/tanoshi-termux/releases/latest)
(asset: `tanoshi-mobile-server-release-<yyyy-MM-dd>.apk`, unchanged name).
0.4.0 adds a hidden silent-audio keep-alive and an auto-restart watchdog so
the background server survives Android/OEM killing more often — see
[Keeping the server alive in the background](#keeping-the-server-alive-in-the-background).

> The Termux package documented below and the Android app in `android/` are two
> independent ways to run the same server. You only need one of them.

### Requirements

| | |
| --- | --- |
| Android | 8.0 (API 26) or newer |
| CPU | **arm64 / aarch64 only** (all modern phones; 32-bit and x86 are not supported) |
| Storage | ~120 MB installed for the app (bundled server + plugins), plus room for your library, downloads and cache |
| Network | Internet for source browsing/downloads; Wi-Fi or Ethernet for sharing to other devices |
| Permissions | Notifications, and "install unknown apps" when you use in-app updates |

### Install

1. Open the
   [latest Android release](https://github.com/spainclaro-debug/tanoshi-termux/releases/latest)
   and download `tanoshi-mobile-server-release-<date>.apk`.
2. Allow your browser or file manager to install unknown apps, open the APK and
   install it.
3. Launch **Tanoshi** and allow notifications (this is the ongoing
   background-server notification).
4. On the **Server** tab, tap **Start server**.

Releases are signed with the same committed sideload key, so new versions
install over an existing install (see [Updating](#updating-the-app)). If you
still have 0.1.0, uninstall it once first — it used a different key.

### First run: create the admin account

The Android app has **no default account** (unlike the Termux package below).
When the server is running, the app offers to create the first administrator
(username and password; it uses Tanoshi's `register` GraphQL mutation). You can
also tap **Skip** and register from the web UI instead. Log in with that
account — the session is kept by the embedded browser.

### What it can do

**Run and supervise the server**

- Runs the bundled Tanoshi server as a **foreground service**: persistent
  notification with a **Stop server** action, partial WakeLock, and the server
  keeps running when you leave the app or swipe it from recents.
- **Server dashboard**: state (stopped/starting/running/stopping/error), PID,
  port, bind address, uptime and the last error, with one-tap start/stop.
- **Logs** tab: live stdout/stderr from the server, follow-output toggle, copy
  all and clear; the same output is mirrored to `server.log` (rotated at 5 MB).
- **Anti-kill protection**: a hidden, inaudible silent-audio keep-alive keeps
  the app in a media-playback foreground state, and an **auto-restart
  watchdog** brings the server back after an unexpected kill (up to 5 times in
  a rolling 10-minute window). The service also resumes the server after an
  Android service restart if it was running. See
  [Keeping the server alive in the background](#keeping-the-server-alive-in-the-background).

**Use the full Tanoshi web UI**

- **Open in app**: an embedded WebView pointing at `http://127.0.0.1:<port>`.
- **Open in browser**: hands the loopback URL to your normal browser.
- This is the real Tanoshi frontend (catalogue, library, reader, downloads,
  sources, trackers, settings). It includes the **infinite-scroll catalogue**:
  the next page loads automatically ~600 px before the bottom, and the button
  changes to *No more results* at the end.

**Share the server with other devices**

- Turn on **Settings → Network → Expose on LAN** (binds `0.0.0.0` instead of
  loopback) and restart the server.
- The **Server** tab then shows a **Share on your network** card: a QR code for
  `http://<host-ip>:<port>`, the host **IP and port** below it, and a copy
  button. Scan the QR from another device on the same Wi-Fi/Ethernet network
  and Tanoshi opens in that device's browser.
- The QR only appears when the server is actually running and bound to the LAN;
  on guest/isolated Wi-Fi it may not be reachable even though the QR scans.

**Configure everything**

| Where | What you can do |
| --- | --- |
| **Settings → Server** | Change the HTTP **port** (1024–65535; restart to apply). Saving works even before the first server start. |
| **Settings → Network** | **Expose on LAN** toggle, with a plain-HTTP warning; the new bind is used on the next start. |
| **Settings → Config** | View and edit the full `config.yml` (paths, secret, update interval, trackers, local folders, download/cache paths). Changes are validated and need a server restart. |
| **Settings → Appearance** | **Dark** (default), **Light**, **Nord** and **Catppuccin** Material 3 themes, applied instantly and remembered. This themes the app UI only; the Tanoshi web UI keeps its own theme. |
| **Settings → Updates** | Check for updates, read the release notes, download the new APK with a progress bar and install it (SHA-256 verified when GitHub exposes an asset digest; the system installer still asks for confirmation). |
| **Settings → Permissions** | Notification permission and "ignore battery optimisations" shortcut. |
| **Settings → Storage** | Show/copy the data directory and server log path. |

**Storage and data**

Everything lives in the app's private storage under `filesDir/tanoshi/`:

```
files/tanoshi/
├── config.yml      # server configuration (port, secret, paths, trackers)
├── tanoshi.db      # SQLite database: accounts, library, history, progress
├── downloads/      # downloaded chapters
├── cache/          # image cache
├── manga/          # "Local" source folder — put your own manga here
├── plugins/        # extension preferences (plugins themselves are in the APK)
└── server.log      # server log, rotated to server.log.1 at 5 MB
```

The bundled extensions are read-only inside the APK and cannot be installed or
updated from Tanoshi's web UI; new/updated plugins arrive with a new app
release. Data survives app updates but is **deleted when you uninstall** the
app (there is no export feature yet), so keep your own backups if needed.

### Keeping the server alive in the background

Android 12+ and many OEM ROMs kill background apps and their **child
processes** aggressively — and the Tanoshi server runs as a child of the app
process. While the server is running the app ships three layers of protection:

- **Silent-audio keep-alive** (0.4.0): the app loops a generated, inaudible
  one-second WAV at volume 0 through `MediaPlayer` and declares the foreground
  service as `specialUse|mediaPlayback`, so Android/OEMs treat it as actively
  playing media. There is **no MediaSession and no extra notification** — the
  only notification stays the normal server one. It is inaudible, has no
  setting/toggle, starts and stops with the server, and adds a small
  continuous battery cost.
- **Auto-restart watchdog** (0.4.0): if the server process is killed
  unexpectedly while you still want it running, the app restarts it
  automatically — up to **5 times in a rolling 10-minute window**, about 3
  seconds apart, keeping the foreground service and audio alive. When the
  budget is used up it shows the `ERROR` state and stays stopped.
- **Battery-optimisation exemption**: on every device, enable **Settings →
  Permissions → Ignore battery optimisations**.

**Limitations — this is best-effort, not a guarantee.** The Android 12+
*phantom-process killer* still limits app child processes: when more than
**32 phantom processes** exist device-wide, or a child uses excessive CPU,
`ActivityManager` kills them. Media playback does **not** exempt the app from
those limits, and OEM task cleaners can still kill it.

How to confirm the phantom-process killer is the cause: the app's **Logs** tab
shows `Tanoshi server process <pid> exited (code ...)` (and then watchdog
restart attempts or the `ERROR` state). With USB debugging,
`adb logcat -s ActivityManager` shows `Killing PhantomProcessRecord`,
`Trimming phantom processes` or an `excessive cpu` reason.

Recommended workaround when it is confirmed (device-side, no root, **not
needed on most phones**):

- **Developer options:** enable Developer options and look for **"Disable
  child process restrictions"** (Samsung/One UI and some other builds expose
  this), then restart the server.
- **ADB, Android 12:**

  ```bash
  adb shell settings put global settings_enable_monitor_phantom_procs false
  adb shell settings get global settings_enable_monitor_phantom_procs  # -> false
  ```

- **ADB, Android 12L/13/14/15:**

  ```bash
  adb shell device_config put activity_manager max_phantom_processes 2147483647
  adb shell device_config get activity_manager max_phantom_processes   # -> 2147483647
  ```

  A reboot may be needed before the change takes effect, and an OS update can
  reset it (re-check and re-apply).

Not every device/OEM build exposes or honours these settings, and a system
update can reset them. They are **device-wide** settings, not app settings:
they affect every app, can increase background memory use and battery drain,
and remove a protection Android added deliberately. Only do it if you actually see the killer in
`logcat`. The app cannot change them itself.

Full details, including the API-level differences and failure modes:
[`android/README.md`](android/README.md#keeping-the-server-alive-in-the-background).

### Updating the app

- **In-app**: **Settings → Updates → Check for updates**. The app reads this
  repository's GitHub releases (only tags like `android-v0.4.0`), compares the
  version, downloads the release APK, verifies it when a digest is available and
  opens the system installer. You must allow "install unknown apps" once and
  confirm the install prompt.
- **Manual**: download `tanoshi-mobile-server-release-<date>.apk` from the
  [latest release](https://github.com/spainclaro-debug/tanoshi-termux/releases/latest)
  and install it over the existing app.
- Updates come from **published releases**, not from every commit: a new app
  version is released by pushing an `android-vX.Y.Z` tag, which the
  [CI workflow](.github/workflows/android-apk.yml) builds and publishes.
- If Android reports a signature mismatch, you are coming from 0.1.0 (whose
  throwaway CI key differed): uninstall once, then install the new APK.

### Security notes

- By default the server is reachable **only from the phone** (loopback). LAN
  mode exposes it to every device on your network.
- There is **no TLS**: LAN traffic, including your Tanoshi password, is plain
  HTTP. Only enable LAN mode on a network you trust and never expose the port
  to the internet.
- The APK is debug-signed and distributed as a **sideload** build (no Play
  Store, no automatic updates except the manual in-app updater).
- The app stores no analytics and talks only to the Tanoshi sources you use,
  plus GitHub when you check for updates.

### Limitations and troubleshooting

| Symptom | What to do |
| --- | --- |
| Server stops when the screen sleeps | Open **Settings → Permissions → Ignore battery optimisations** and exempt Tanoshi from your OEM's battery saver. If it still dies, it may be the Android 12+ phantom-process killer — see [Keeping the server alive in the background](#keeping-the-server-alive-in-the-background). |
| Server keeps restarting itself, then shows ERROR | That is the 0.4.0 auto-restart watchdog: the server was killed up to 5 times in 10 minutes. Check the **Logs** tab and the phantom-process-killer guidance in [Keeping the server alive in the background](#keeping-the-server-alive-in-the-background). |
| "Port already in use" / server won't start | Pick a different port in **Settings → Server** and restart. |
| No QR code on the Server tab | Enable **Expose on LAN**, restart the server, and make sure the phone is on Wi-Fi/Ethernet (not just mobile data). |
| QR scans but the other device can't connect | Guest Wi-Fi / client isolation or a VPN is blocking it; both devices must be on the same network. Use the shown `IP:port` in a browser to test. |
| Old versions: error saving the port (`config.yml.tmp`, `ENOENT`) | Fixed in 0.3.0 — update the app. |
| Update check fails | Needs network access; GitHub rate limits can apply. Download the APK from the release page manually. |
| Plugins missing / a source disappeared | The app ships a fixed set of plugins; check the Logs tab and update to the latest app release. |
| Other problems | **Logs** tab and `server.log`; full troubleshooting in [`android/README.md`](android/README.md#troubleshooting). |

### Building the Android app

The project is a normal Gradle/Android Studio project in `android/` (JDK 17,
AGP 8.7.3, compileSdk 35). Native binaries (Tanoshi server, `libarchive.so`,
the nine plugins) are prebuilt and checked into `jniLibs/`:

```bash
cd android
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/tanoshi-mobile-server-release-<date>.apk
```

The CI workflow builds the APK on every push and publishes it for
`android-v*` tags. To rebuild the native server or plugins, follow
[`android/native/README.md`](android/native/README.md).

### More

- Full app documentation: [`android/README.md`](android/README.md)
- Changelog: [`android/CHANGELOG.md`](android/CHANGELOG.md)
- Assumptions and open questions:
  [`android/ASSUMPTIONS_AND_OPEN_QUESTIONS.md`](android/ASSUMPTIONS_AND_OPEN_QUESTIONS.md)
- Releases: <https://github.com/spainclaro-debug/tanoshi-termux/releases>

---

## Default login

After installation the server has one account:

| Username | Password | Role |
| -------- | -------- | ---- |
| `admin` | `tanoshi123` | Administrator |

> **Change this password** the first time you log in (Settings → account). It is
> a well-known default, and the server listens on all interfaces.

You can choose different credentials at install time:

```bash
bash install.sh --user myname --password 'something-strong'
```

---

## Requirements

- Termux from [F-Droid](https://f-droid.org/packages/com.termux/) or GitHub
  releases *(not the Play Store build, which is outdated)*
- Android on **arm64 / aarch64** (all modern phones)
- ~250 MB free disk for a prebuilt install; ~6 GB if you later rebuild from source

Check your architecture:

```bash
uname -m          # expect: aarch64
```

---

## Quick start

One command does everything — extract, install and set up:

```bash
tar -xzf tanoshi-termux-aarch64.tar.gz && cd tanoshi && bash install.sh
```

Then open **http://127.0.0.1:8080** in your phone's browser and log in with
`admin` / `tanoshi123`.

Or step by step:

```bash
# 1. Install the basic Termux tools once
pkg update -y && pkg install -y tar curl python

# 2. Extract the package (it unpacks into ./tanoshi)
tar -xzf tanoshi-termux-aarch64.tar.gz
cd tanoshi

# 3. Install and start
bash install.sh
```

### Installer options

| Option | Meaning |
| --- | --- |
| `--port N` | HTTP port (default `8080`) |
| `--user NAME` | admin username (default `admin`) |
| `--password PASS` | admin password (default `tanoshi123`) |
| `--from-source` | build everything locally instead of using bundled binaries |
| `--with-build-tools` | also install the Rust/wasm toolchain so updates can rebuild locally |
| `--no-start` | set up without starting the server |
| `--no-admin` | do not create the initial admin account |
| `--yes` | assume yes for prompts |

Examples:

```bash
bash install.sh --port 9000                  # different port
bash install.sh --with-build-tools           # enable local rebuilds for updates
bash install.sh --from-source                # ignore bundled binaries, build fresh
```

---

## Using the server

A control command is added to your `PATH` as `tanoshi-ctl`:

```bash
tanoshi-ctl start      # start in the background
tanoshi-ctl stop       # stop it
tanoshi-ctl restart    # restart
tanoshi-ctl status     # running? which URL?
tanoshi-ctl url        # print the web UI URL
tanoshi-ctl logs       # follow the live log (Ctrl+C to stop)
```

The equivalent script inside the folder is `./tanoshi.sh`.

Other maintenance scripts in the same folder:

| Script | Purpose |
| --- | --- |
| `bash selfupdate.sh` | update from the newest **prebuilt package** in this project's GitHub repo (fast, no toolchain) |
| `bash update.sh` | rebuild from the newest **upstream Tanoshi release** (needs the Rust toolchain) |
| `bash reinstall.sh` | repair the install (keeps data) |
| `bash uninstall.sh` | remove Tanoshi (backs up data first) |
| `bash install.sh` | (re)run setup |

### Browsing

The catalogue loads the next page automatically as you scroll: a sentinel below
the grid starts fetching when it comes within ~600px of the viewport, so the
next batch is usually ready before you reach the bottom. The **Load More**
button remains as a manual fallback and changes to *No more results* once a
source runs out of pages.

### Where everything lives

```
tanoshi/
├── bin/tanoshi          # the server binary (arm64 Android)
├── plugins/*.so         # extension plugins
├── data/                # TANOSHI_HOME — all your data
│   ├── config.yml       # configuration (port, paths, secret)
│   ├── tanoshi.db       # SQLite database (users, library, history)
│   ├── downloads/       # downloaded chapters
│   ├── cache/           # image cache
│   ├── manga/           # "Local" source folder
│   └── tanoshi.log      # server log
├── src/                 # Tanoshi source (patched for Termux)
├── extensions/          # the four ported Keiyoushi extensions
├── patches/             # Android patches + vendored patches
├── vendor/              # patched compress-tools crate
├── install.sh           # setup script
├── update.sh            # rebuild from upstream source
├── PACKAGE_TAG          # which prebuilt package this is
├── reinstall.sh         # repair an install, keep or reset data
├── selfupdate.sh        # update from this project's GitHub releases
├── uninstall.sh         # remove Tanoshi (backs up data first)
└── tanoshi.sh           # service control
```

Everything is inside the `tanoshi/` folder, so you can move it, back it up, or
delete it cleanly. Back up `data/` — that is your whole library.

### Reading from another device on your LAN

The server listens on `0.0.0.0`, so other devices can reach it at
`http://<phone-ip>:8080`. Find the phone's IP with `ifconfig wlan0` or in
Android's Wi-Fi settings.

> There is **no TLS**. Traffic, including your password, is unencrypted on the
> LAN. Only do this on a network you trust, or put a reverse proxy with HTTPS in
> front of it.

---

## Updating

There are two updaters. Use **selfupdate.sh** unless you specifically want to
build Tanoshi yourself.

### From the prebuilt package (recommended)

Pulls the newest package published in
[spainclaro-debug/tanoshi-termux](https://github.com/spainclaro-debug/tanoshi-termux),
verifies its checksum and applies it. No Rust toolchain needed, takes seconds.

```bash
cd tanoshi
bash selfupdate.sh --check    # is there a newer package?
bash selfupdate.sh            # download, verify and install it
bash selfupdate.sh --rollback # undo the last package update
```

How it works:

1. Asks the GitHub API for the newest release in this project's repo, and
   compares its tag (`v0.40.0-termux.2`) with the local `PACKAGE_TAG`. A release
   is never applied backwards.
2. Downloads the archive and its `.sha256`, and **refuses to install on a
   checksum mismatch**.
3. Extracts to a temporary directory and checks it really contains
   `bin/tanoshi` and `plugins/`.
4. Backs up the current binary and plugins to `backups/selfupdate-<timestamp>/`
   (five newest kept).
5. Swaps in the new binary, plugins and helper scripts. Plugins the new package
   no longer ships are removed; runtime scratch files are cleared.
6. Repairs `config.yml` if needed, restarts and reports how many plugins loaded.

Your `data/` directory — library, accounts, history, downloads — is **never
touched**. Only program files are replaced.

If the API rate-limits you, wait an hour and retry; the script also falls back
to the archive committed in the repository if a release has no assets.

### From upstream source (rebuild)

Use this to follow upstream Tanoshi itself rather than this package:

```bash
cd tanoshi
bash update.sh --check     # is there a newer upstream release?
bash update.sh             # rebuild and install the latest release
```

What the updater does:

1. Asks GitHub for the newest release tag and compares it with `VERSION`.
2. Backs up the current binary and plugins into `backups/<timestamp>/`
   (the five newest backups are kept).
3. Clones that exact tag, applies the Android patches, and rebuilds the wasm web
   UI and the server.
4. If the new release changed `tanoshi-lib`'s version, **rebuilds the extension
   plugins too** — plugins embed that version and refuse to load on a mismatch.
5. Restarts the server and verifies that it reports the new version and that the
   expected number of plugins loaded.

Options:

| Option | Meaning |
| --- | --- |
| `--check` | report whether an update exists, change nothing |
| `--force` | rebuild even if already up to date |
| `--server-only` | never rebuild extensions (fast, only safe if lib version is unchanged) |
| `--with-extensions` | always rebuild extensions |
| `--rollback` | restore the most recent backup |
| `--yes` | assume yes for prompts |

If an update goes wrong:

```bash
bash update.sh --rollback
```

The first update needs the Rust toolchain (~160 MB download plus a
`wasm-bindgen` compile). If it is missing, the updater installs it
automatically. Pre-installing it during setup avoids the wait later:

```bash
bash install.sh --with-build-tools
```

> Updating replaces `bin/` and `plugins/` only. `data/` — your account, library,
> reading history and settings — is never touched.

---

## Extensions

Nine sources are bundled and load automatically:

| Extension | Language | Notes |
| --- | --- | --- |
| Mangadex | multi | |
| Guya | multi | |
| Manhwa18cc | multi | NSFW |
| WeebCentral | en | |
| **Atsumaru** | en | ported from Keiyoushi |
| **QiScans** | en | ported from Keiyoushi |
| **Manga Ball** | en | ported from Keiyoushi; English chapters only |
| **MadaraDex** | en | ported from Keiyoushi |
| **HentaiNexus** | en | ported from Keiyoushi; NSFW; one chapter per gallery |

The last five are **Rust ports of [Keiyoushi](https://github.com/keiyoushi/extensions)
(Tachiyomi) extensions**, rewritten against Tanoshi's plugin API. Their sources
live in `extensions/` and the upstream ids are preserved.

Install or remove sources from the web UI (admin account required).

### Why can't I just install a Tachiyomi/Keiyoushi `.apk`?

Because they are not the same kind of plugin. Keiyoushi extensions are Kotlin
bytecode that runs inside an Android app's JVM; Tanoshi plugins are native Rust
libraries (`.so`) loaded by the server. They cannot be interchanged. The four
above were ported source-by-source to bridge that gap.

---

## Troubleshooting

**The server does not start**

```bash
tanoshi-ctl status
tanoshi-ctl logs 100
```

The usual cause is a port already in use. Change it in `data/config.yml`
(`port:`) and run `tanoshi-ctl restart`.

**"Port 80 is not permitted"** — Android does not allow binding privileged ports
for normal apps. Keep a port ≥ 1024.

**Plugins do not appear in the UI** — the plugins and the server disagree on the
`tanoshi-lib` version. Check `LIB_VERSION` against the log line
`Version mismatch: extension.lib_version=...`. Fix by rebuilding:

```bash
bash update.sh --with-extensions --force
```

**"Fetch manga from source failed: error decoding response body"** — this means
your `data/config.yml` has a `secret` that is not exactly **16 bytes**. Tanoshi
encrypts cover images with AES-128-CBC and the key length is fixed, so any other
length makes every `coverUrl` fail. Repair it automatically:

```bash
bash reinstall.sh
```

That detects the bad length, writes a new 16-character secret and restarts.
Because the secret signs login tokens, you will have to **log in again**.

**A source stopped working** — these are scrapers of third-party sites; they
break when a site changes. Run `bash update.sh` to pick up rebuilt extensions.

**Termux kills the server when the app closes** — Android suspends background
processes. Options:

- Keep Termux open (swipe it away in the notification).
- Run `termux-wake-lock` before starting, and acquire the notification.
- Use `pkg install termux-services` and run Tanoshi under `svir`.
- Install [Termux:Boot](https://f-droid.org/packages/com.termux.boot/) and add
  `tanoshi-ctl start` to `~/.termux/boot/`.

**Reset everything** — stop the server, delete `data/`, then re-run
`bash install.sh`. That recreates the database and the admin account. This
deletes your library.

**Move to a new phone** — copy the whole `tanoshi/` folder across, then run
`bash install.sh` (it keeps the existing `data/`) or just
`bash tanoshi.sh start`.

---

## Security notes

- The bundled admin password is public. **Change it immediately.**
- There is no HTTPS. Do not expose port 8080 to the internet without a reverse
  proxy and TLS.
- `data/config.yml` contains the JWT signing secret; it is created with mode
  `600`. Keep it private — anyone with it can forge login tokens.
- Tanoshi fetches content from third-party sites chosen by its extensions.
  Respect their terms of service and the laws of your country.

## Reinstalling / repairing

If something breaks — a corrupted `config.yml`, a deleted data folder, a broken
`tanoshi-ctl`, wrong permissions, or plugins that stopped loading — repair the
install in place instead of starting over:

```bash
cd tanoshi
bash reinstall.sh
```

It stops the server, backs up `config.yml` and `tanoshi.db` into
`backups/reinstall-<timestamp>/`, recreates any missing folders and files,
resets permissions, clears stale plugin copies, reinstalls the control command
and restarts. **Your library, accounts and history are kept.**

To also wipe the data and start fresh:

```bash
bash reinstall.sh --reset-data
```

The old data is never deleted — it is moved to `data.old-<timestamp>/` so you can
recover from it, and a new admin account is created.

| Option | Meaning |
| --- | --- |
| `--reset-data` | move `data/` aside and start clean (recreates admin) |
| `--port N` | port to use if `config.yml` must be recreated |
| `--user` / `--password` | admin credentials for a reset |
| `--no-start` | repair files but leave the server stopped |
| `--yes` | assume yes for prompts |

`reinstall.sh` never reinstalls the program binaries from scratch — for a fresh
program build use `update.sh` or `install.sh --from-source`.

## Uninstall

```bash
cd tanoshi
bash uninstall.sh
```

It stops the server, backs up your data to `~/tanoshi-backup-<timestamp>.tar.gz`,
removes `tanoshi-ctl` from your `PATH`, and deletes the folder.

| Option | Meaning |
| --- | --- |
| `--keep-data` | delete the program but keep `data/` for a later reinstall |
| `--no-backup` | skip the backup (your data will be gone permanently) |
| `--yes` | assume yes for prompts |

To restore a backup into a fresh install:

```bash
tar -xzf ~/tanoshi-backup-<timestamp>.tar.gz -C /path/to/tanoshi
```

Doing the steps by hand instead:

```bash
tanoshi-ctl stop
rm -f "$PREFIX/bin/tanoshi-ctl"
rm -rf ~/tanoshi            # or wherever the folder is
```

## Credits and licences

- Tanoshi © luigi311, MIT — <https://github.com/luigi311/tanoshi>
- Extensions © luigi311 — <https://github.com/luigi311/tanoshi-extensions>
- The four ported extensions are derived from
  [Keiyoushi](https://github.com/keiyoushi/extensions) (Apache-2.0) and adapted
  to Tanoshi's plugin API.
- Android/Termux patches in `patches/` are local modifications, documented in
  `patches/README-patches.txt`.
