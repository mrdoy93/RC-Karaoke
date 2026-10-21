plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.offlinekaraoke"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.offlinekaraoke"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.9.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        // Packed songs and videos must remain seekable through AssetFileDescriptor.
        noCompress += listOf("kpack", "mp4", "m4v", "webm", "mkv", "3gp")
    }

    sourceSets {
        getByName("main") {
            // Package reusable video backgrounds directly from the workspace folder.
            assets.srcDir(rootProject.file("Background"))
        }
    }
}

dependencies {
    // Intentionally empty: this prototype uses only Android framework APIs.
}
