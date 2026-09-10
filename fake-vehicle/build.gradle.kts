plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.qf.vehicle"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.qf.vehicle"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "stub-1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
