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
        versionCode = 7
        versionName = "1.6"

        buildConfigField("String", "APP_URL", "\"${cfg("APP_URL")}\"")
        buildConfigField("String", "FCM_PROJECT_ID", "\"${cfg("FCM_PROJECT_ID")}\"")
        buildConfigField("String", "FCM_API_KEY", "\"${cfg("FCM_API_KEY")}\"")
        buildConfigField("String", "FCM_APP_ID", "\"${cfg("FCM_APP_ID")}\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"${cfg("FCM_SENDER_ID")}\"")
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
    implementation("com.google.firebase:firebase-messaging:24.1.0")
    implementation("com.google.firebase:firebase-common:21.0.0")
}
