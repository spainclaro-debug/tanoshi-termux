# Changelog — Tanoshi for Android

All notable changes to the standalone Android app in `android/`. The app is
versioned independently of the upstream Tanoshi server it bundles (currently
Tanoshi **0.40.0**) and is released as GitHub releases tagged **`android-vX.Y.Z`**
(for example `android-v0.3.0`). The Termux package in the repository root has
its own `PACKAGE_TAG` and `v*` release tags.

Versioning: local/Gradle builds default to the version in
`android/app/build.gradle.kts`. CI derives `versionName` from the
`android-vX.Y.Z` tag and sets
`versionCode = major * 10000 + minor * 100 + patch`, passing both to Gradle
with `-PappVersionName` / `-PappVersionCode`. Since 0.3.0 the sideload APK is
named `tanoshi-mobile-server-release-<yyyy-MM-dd>.apk`, where the date is the
UTC build date in CI (via `-PapkDate`) and the local date otherwise.

---

## 0.3.0

### Added

- **"Share on your network" QR card** on the Server screen: while the server
  is running and bound to `0.0.0.0` (Expose on LAN), it shows a QR code
  encoding `http://<host-ipv4>:<port>` for another device on the same
  Wi-Fi/Ethernet network, plus the host `ip:port`/URL with a copy button. The
  address prefers a Wi-Fi/Ethernet IPv4 address (excluding loopback and
  link-local) and is refreshed when the screen resumes. QR generation uses
  ZXing core 3.5.3 (pure Java) off the main thread. When one of the
  requirements is missing, the card explains what to enable instead. Includes
  plain-HTTP/same-network warnings.

### Fixed

- **Saving the port or `config.yml` before the first server start** failed with
  `config.yml.tmp: open failed: ENOENT (No such file or directory)` because
  the data directory did not exist. `ConfigManager.setPort` now ensures the
  app directories exist, and the atomic write creates the target's parent
  directory defensively.

### Changed

- **Sideload APK renamed** from `app-debug.apk` to
  `tanoshi-mobile-server-release-<yyyy-MM-dd>.apk`. CI passes the UTC build
  date via `-PapkDate`; local builds use the local date. CI uploads the dated
  file inside the `tanoshi-android-debug-apk` artifact and publishes it as the
  GitHub release asset. The in-app updater prefers release assets whose name
  starts with `tanoshi-mobile-server` and still accepts legacy
  `app-debug.apk` assets (0.1.0/0.2.0 releases).
- **Version 0.3.0:** `versionCode` 3 in the Gradle default; the
  `android-v0.3.0` tag produces `versionCode` 300.

## 0.2.0

### Added

- **In-app updates** (`Settings → Updates`): checks this repository's public
  GitHub releases (unauthenticated, only non-draft/non-prerelease `android-v*`
  tags), compares the version numerically with the installed app, downloads
  the release APK with a progress indicator (SHA-256 verified when the GitHub
  release asset exposes a digest) and installs it through the system
  `PackageInstaller`. Requires network, the "Install unknown apps" permission
  for Tanoshi, and the user's confirmation in the system install prompt.
- **Selectable app themes** (`Settings → Appearance`): Dark (default), Light,
  Nord and Catppuccin Material 3 colour schemes. The choice applies
  immediately, is persisted (`AppSettings.themeId`) and also switches the
  system-bar icon contrast. It affects the app UI only — the embedded Tanoshi
  web UI keeps its own theme.
- `REQUEST_INSTALL_PACKAGES` permission for the updater.

### Changed

- **Embedded web UI rebuilt with catalogue infinite scroll.** The vendored
  `android/native/web-dist/` is now the package-3 web UI built with
  `patches/tanoshi-infinite-scroll.patch`; `libtanoshi.so` was rebuilt with it.
  The catalogue now loads the next page automatically when an invisible
  sentinel below the grid (`catalogue-scroll-sentinel`) approaches the
  viewport; **Load More** stays as a manual fallback and becomes *No more
  results* when a source runs out of pages. The web UI is embedded in the
  server binary, so web-UI behaviour changes require rebuilding
  `libtanoshi.so`, not the Android app.
- **Stable sideload signing.** All releases (CI and local) are now signed with
  the committed key `android/keystore/tanoshi-signing.jks` (alias `tanoshi`)
  so the in-app updater can install a newer release over the existing app.
  The 0.2.0 release asset was still named `app-debug.apk`.
  The `debug` and `release` build types both use this key when the file is
  present, and fall back to the local Android debug keystore otherwise. See
  `android/keystore/README.md`.
- **Version/CI:** the app is 0.2.0 (`versionCode` 2 in the Gradle default;
  `android-v0.2.0` tags produce `versionCode` 200). CI computes both from the
  tag, and only `android-v*` tags trigger the workflow — plain `v*` Termux
  package tags are ignored.

### Upgrade note

**Users of 0.1.0 must uninstall it once before installing 0.2.0.** The 0.1.0
APK was signed with an ephemeral CI debug key, so Android refuses to install
0.2.0 over it. Uninstalling deletes the app's private data (library, accounts,
downloads); copy the `files/tanoshi/` directory out first if you need it.
This is a one-time step: every later release shares the committed signing key
and updates in place.

## 0.1.0

First standalone Android release (no Termux):

- Tanoshi 0.40.0 server (`libtanoshi.so`) and the nine extensions built for
  `aarch64-linux-android`, executed from `nativeLibraryDir`.
- Minimal `libarchive` 3.8.11 (zlib linked statically).
- Foreground `specialUse` service with wake lock, notifications and start/stop
  control; server data under `filesDir/tanoshi/` (`TANOSHI_HOME`).
- Compose UI: server dashboard, live log viewer, embedded WebView and browser
  hand-off, port / LAN exposure / `config.yml` editing, notification and
  battery-optimisation settings.
- First-run administrator creation through the server's GraphQL `register`
  mutation (no baked-in credentials).
- Debug-signed, arm64-v8a-only APK (`app-debug.apk`) built by GitHub Actions
  on pushes, pull requests and `android-v*` tags.
