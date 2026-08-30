plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lux032.musicbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lux032.musicbridge"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "v0.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = "musicbridge123"
            keyAlias = "musicbridge"
            keyPassword = "musicbridge123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // Extracts dominant/vibrant colors from album artwork so the player screen can tint
    // its background and accents after the current cover.
    implementation(libs.androidx.palette.ktx)
    // On-device playback: ExoPlayer decodes opus/flac/mp3 natively and handles audio
    // focus; the cache datasource backs the user-configurable local audio cache.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.datasource)
    // Publishes the on-device player as a system MediaSession so local playback gets the
    // standard media notification, lockscreen controls and Bluetooth/headset buttons.
    implementation(libs.androidx.media3.session)
    // Installs the baseline profiles shipped inside Compose artifacts so release builds get
    // AOT-compiled scroll/animation hot paths instead of warming up through JIT.
    implementation(libs.androidx.profileinstaller)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
