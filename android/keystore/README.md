# Signing key

`tanoshi-signing.jks` is the **shared sideload signing key** used for every
Tanoshi Android release in this repository (CI and local builds alike).

Why is a keystore committed here? Android only installs an update over an
existing app when the APK is signed with the **same key**. The in-app updater
(`Settings -> Updates`) downloads the next release APK from GitHub and hands it
to the system installer, so all releases must be signed identically. This app is
distributed only as a sideload APK (no Play Store), so the key is not a secret
in any meaningful sense — it only needs to be stable.

| Field | Value |
| --- | --- |
| File | `android/keystore/tanoshi-signing.jks` |
| Store type | JKS |
| Key alias | `tanoshi` |
| Store password | `tanoshi-android` |
| Key password | `tanoshi-android` |
| Subject | `CN=Tanoshi Android, OU=Sideload, O=spainclaro-debug, C=PH` |
| Validity | ~30 years (until 2056) |

`android/app/build.gradle.kts` signs both the `debug` and `release` variants
with this key when the file is present, and falls back to the local Android
debug keystore when it is not.

## Using your own key

1. Generate a key (or reuse yours):

   ```bash
   keytool -genkeypair -v -keystore android/keystore/tanoshi-signing.jks \
     -storetype JKS -alias tanoshi -keyalg RSA -keysize 2048 -validity 10950 \
     -storepass tanoshi-android -keypass tanoshi-android \
     -dname "CN=Your Name, O=Your Org, C=XX"
   ```

2. Either keep the file name/passwords (simplest) or edit the signing block in
   `android/app/build.gradle.kts` to read them from Gradle properties /
   environment variables.

Changing the key breaks in-app updates for already installed builds: users must
uninstall the old app once (which also deletes its data) before installing an
APK signed with the new key.
