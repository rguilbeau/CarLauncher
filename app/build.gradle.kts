plugins {
    alias(libs.plugins.android.application)
}

val code = project.findProperty("versionCode")?.toString()?.toInt() ?: 1
val name = project.findProperty("versionName")?.toString() ?: "0.0.0-dev"

android {
    namespace = "com.rguilbeau.carlauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rguilbeau.carlauncher"
        minSdk = 24
        targetSdk = 35
        versionCode = code
        versionName = name

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release_key")
            storePassword = "CarLauncher"
            keyAlias = "key0"
            keyPassword = "CarLauncher"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // On applique la signature
            signingConfig = signingConfigs.getByName("release")
        }

        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation("com.intuit.sdp:sdp-android:1.1.1")
    implementation("com.intuit.ssp:ssp-android:1.1.1")
    implementation("androidx.palette:palette:1.0.0")
    implementation("com.elvishew:xlog:1.11.1")
    implementation("com.google.zxing:core:3.5.2")
    // Outils pour la position GPS
    implementation("com.google.android.gms:play-services-location:21.1.0")
    // Outil pour faire des requêtes internet
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}