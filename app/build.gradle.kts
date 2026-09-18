import java.util.Properties

plugins {
    id("com.android.application")
}

// Keystore de release (généré par scripts/init-keystore.sh, hors dépôt).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.keepclaw"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.keepclaw"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (keystoreProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // IMPORTANT KeepClaw : les binaires Go vivent dans jniLibs/arm64-v8a sous le nom lib*.so
    // pour être extraits par PackageManager vers nativeLibraryDir (zone exec-able).
    androidResources {
        noCompress += listOf("so")
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true   // extractNativeLibs -> vrai fichier .so sur disque
            keepDebugSymbols += listOf("**/libpicoclaw.so", "**/liblauncher.so")
        }
    }

    lint {
        abortOnError = false
    }
}

// Aucune dépendance externe : Java pur + API plateforme (WebView, Service, TileService).
dependencies { }
