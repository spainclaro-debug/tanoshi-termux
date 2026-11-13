# Tanoshi for Termux

A self-contained, ready-to-run build of [Tanoshi](https://github.com/luigi311/tanoshi)
— a self-hosted web manga reader — packaged for **Termux on Android (arm64)**.

Upstream ships no Android binary (only glibc Linux, Windows and macOS), so this
package contains a native `aarch64` Android build plus nine source extensions,
and the patch set needed to rebuild everything on Termux.

---

## Android app (no Termux)

A standalone Android app lives in [`android/`](android/README.md). It bundles
the same Tanoshi server and the same nine extensions as a single sideloadable
APK — no Termux, no shell — and runs the server from a foreground service with
an in-app web UI.

- **No default account:** the app creates no administrator; you register the
  first admin on first run.
- **arm64 only:** Android 8.0+ (API 26) on an arm64 / aarch64 device.
- **Docs:** [`android/README.md`](android/README.md) covers building,
  sideloading, first-run onboarding, settings and troubleshooting; the native
  rebuild pipeline is in
  [`android/native/README.md`](android/native/README.md).

The Termux package documented below and the APK project in `android/` are two
independent ways to run the same server.

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
