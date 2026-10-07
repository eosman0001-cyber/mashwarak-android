import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val cfg = Properties()
val cfgFile = rootProject.file("mashwarak-config.properties")
if (cfgFile.exists()) cfgFile.inputStream().use { cfg.load(it) }

fun cfg(name: String, fallback: String = "") =
    (cfg.getProperty(name) ?: fallback).replace("\\", "\\\\").replace("\"", "\\\"")

val releaseVersionName = System.getenv("MASHWARAK_VERSION_NAME")?.trim().orEmpty().ifBlank { "1.13.0" }
val releaseVersionCode = System.getenv("MASHWARAK_VERSION_CODE")?.trim()?.toIntOrNull() ?: 27

android {
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("MASHWARAK_KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank()) {
                storeFile = file(ksPath)
                storeType = "pkcs12"
                storePassword = System.getenv("MASHWARAK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MASHWARAK_KEY_ALIAS")
                keyPassword = System.getenv("MASHWARAK_KEY_PASSWORD")
            }
        }
    }

    namespace = "com.jekonix.mashwarak"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jekonix.mashwarak"
        minSdk = 24
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        buildConfigField("String", "APP_URL", "\"${cfg("APP_URL")}\"")
        buildConfigField("String", "FCM_PROJECT_ID", "\"${cfg("FCM_PROJECT_ID")}\"")
        buildConfigField("String", "FCM_API_KEY", "\"${cfg("FCM_API_KEY")}\"")
        buildConfigField("String", "FCM_APP_ID", "\"${cfg("FCM_APP_ID")}\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"${cfg("FCM_SENDER_ID")}\"")

        val mapsApiKey = System.getenv("MASHWARAK_MAPS_API_KEY")?.trim().orEmpty().ifBlank {
            (cfg.getProperty("GOOGLE_MAPS_API_KEY") ?: cfg.getProperty("FCM_API_KEY") ?: "").trim()
        }
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.firebase:firebase-messaging:24.1.0")
    implementation("com.google.firebase:firebase-common:21.0.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-maps:20.0.0")
}
