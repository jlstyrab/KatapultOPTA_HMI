// KatapultOPTA_HMI — WebView-skal utan bibliotek (D3). Runtimen ligger i
// src/main/assets/www (synkas med tools/sync-runtime.sh ur katapult-scada).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Runtimens commit (skrivs av sync-skriptet) följer med i versionsnamnet.
val runtimeVersion: String = file("src/main/assets/www/RUNTIME_VERSION")
    .let { if (it.exists()) it.readText().trim() else "dev" }
val buildNumber: Int = (System.getenv("APK_VERSION_CODE") ?: "1").toIntOrNull() ?: 1

android {
    namespace = "se.jlstyr.katapultopta"
    compileSdk = 34

    defaultConfig {
        applicationId = "se.jlstyr.katapultopta"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.$buildNumber+$runtimeVersion"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Avsiktligt tomt: inga AndroidX-bibliotek, bara Android-ramverket + Kotlins stdlib.
dependencies {
}
