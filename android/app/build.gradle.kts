plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version can be overridden from CI (`-PappVersionName=0.2.0 -PappVersionCode=200`)
// so tagged Android releases always carry an increasing version automatically.
val appVersionName: String = providers.gradleProperty("appVersionName").orElse("0.2.0").get()
val appVersionCode: Int = providers.gradleProperty("appVersionCode").orElse("2").get().toInt()

// Shared sideload signing key. Committing a keystore is normally a bad idea,
// but this app is only distributed as a sideloadable APK and every release
// must be signed with the SAME key, otherwise Android refuses to install an
// update over the existing app. Replace it with your own key for a private
// build (see android/keystore/README.md).
val signingKeystore = rootProject.file("keystore/tanoshi-signing.jks")
val useSharedKeystore = signingKeystore.exists()

android {
    namespace = "dev.spainclaro.tanoshi"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    signingConfigs {
        create("tanoshi") {
            if (useSharedKeystore) {
                storeFile = signingKeystore
                storePassword = "tanoshi-android"
                keyAlias = "tanoshi"
                keyPassword = "tanoshi-android"
            }
        }
    }

    defaultConfig {
        applicationId = "dev.spainclaro.tanoshi"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        // The bundled Tanoshi server and all extension plugins are built for
        // arm64 (aarch64-linux-android) only. Restricting the ABI keeps the APK
        // smaller and prevents installing it on 32-bit devices that could not
        // run the server anyway.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = if (useSharedKeystore) {
                signingConfigs.getByName("tanoshi")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        release {
            isMinifyEnabled = false
            // Releases are only ever sideloaded; sign them with the same key
            // as the debug build so in-app updates can replace an installed
            // build. Distribution uses the debug variant (`.debug`
            // applicationId), which is what CI publishes.
            // See android/keystore/README.md.
            signingConfig = if (useSharedKeystore) {
                signingConfigs.getByName("tanoshi")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The bundled Tanoshi binary is executed from nativeLibraryDir, so the
    // installer must extract native libraries (Android 10+ forbids exec from
    // the app data directory, but nativeLibraryDir is executable).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        // The Tanoshi web UI (embedded in the binary) is served by the native
        // server; the app itself only needs its own small resources.
        noCompress += listOf("yml", "yaml", "db")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
