plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "com.lux032.musicbridge.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    buildTypes {
        create("benchmark") {
            isDebuggable = false
            isMinifyEnabled = true
            matchingFallbacks += listOf("benchmark", "release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
            )
            testProguardFiles(file("benchmark-proguard-rules.pro"))
        }
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.uiautomator)
    implementation("androidx.arch.core:core-runtime:2.2.0")
    implementation("androidx.startup:startup-runtime:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
