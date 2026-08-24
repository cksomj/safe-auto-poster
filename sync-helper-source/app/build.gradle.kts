plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dean.synchelper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dean.synchelper"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-localwifi"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
