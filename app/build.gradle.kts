import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val code = project.findProperty("versionCode")?.toString()?.toInt() ?: 1
val name = project.findProperty("versionName")?.toString() ?: "0.0.0-dev"

// Secrets locaux (local.properties, non commité) avec repli sur les variables d'environnement (CI)
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
fun secret(key: String): String = (localProperties.getProperty(key) ?: System.getenv(key) ?: "").trim()

// Échappe la valeur pour une insertion sûre dans un literal String Java (buildConfigField)
fun secretLiteral(key: String): String {
    val escaped = secret(key)
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$escaped\""
}

android {
    namespace = "com.rguilbeau.carlauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rguilbeau.carlauncher"
        minSdk = 26
        targetSdk = 35
        versionCode = code
        versionName = name

        buildConfigField("String", "DB_URL", secretLiteral("DB_URL"))
        buildConfigField("String", "DB_USER", secretLiteral("DB_USER"))
        buildConfigField("String", "DB_PASSWORD", secretLiteral("DB_PASSWORD"))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release_key")
            storePassword = secret("SIGNING_STORE_PASSWORD")
            keyAlias = "key0"
            keyPassword = secret("SIGNING_KEY_PASSWORD")
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
    implementation("org.postgresql:postgresql:42.7.13")
    // Outils pour la position GPS
    implementation("com.google.android.gms:play-services-location:21.1.0")
    // Outil pour faire des requêtes internet
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    // File d'attente persistante (contrainte réseau) pour les écritures en base de données
    implementation("androidx.work:work-runtime:2.10.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}