import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// google-services.json comes from Firebase console > Project settings > Your apps > Android.
// The plugin is only applied when the file exists so the project still builds without it.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.warn("MeetSnap: app/google-services.json missing: Firebase will not initialize at runtime.")
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.meetsnap.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.meetsnap.app"
        minSdk = 26 // required by firebase-ai-ondevice
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // Set -PappcheckDebug=true to use the App Check debug provider in a release build (for judges).
        buildConfigField("boolean", "APPCHECK_DEBUG", (project.findProperty("appcheckDebug") ?: "false").toString())

        // Fixed App Check debug token, so you register it once in the console instead of reading
        // a random one from logcat. Put `appcheck.debugToken=<uuid>` in android/local.properties
        // (not committed). The SDK only honours it in debuggable builds.
        localProps.getProperty("appcheck.debugToken")?.takeIf { it.isNotBlank() }?.let {
            resValue("string", "firebase_app_check_debug_token", it)
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so judges can sideload the release APK.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Firebase (shared backend with the web app)
    implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
    implementation("com.google.firebase:firebase-ai")
    implementation("com.google.firebase:firebase-ai-ondevice:16.0.0-beta08") // not in the BoM yet
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-config")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("com.google.firebase:firebase-appcheck-debug")


    // UI
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")

    // Camera
    implementation("androidx.camera:camera-camera2:1.5.1")
    implementation("androidx.camera:camera-lifecycle:1.5.1")
    implementation("androidx.camera:camera-view:1.5.1")
    implementation("com.google.guava:guava:33.4.8-android")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
