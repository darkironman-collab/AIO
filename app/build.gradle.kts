plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.codex.aio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.codex.aio"
        minSdk = 26
        // Kept below 29 so the sideloaded app can execute the bundled Linux
        // runtime from its private app directory on modern Android devices.
        targetSdk = 28
        versionCode = 2
        versionName = "1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libproot.so"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
}
