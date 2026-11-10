plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.willingtocode.voicepathfinder"
    compileSdk = 37

    defaultConfig {
        // A different applicationId from the real app, so both can be installed at
        // once. Testing a call is much easier when the phone can still show the
        // message that arrived while it was ringing.
        applicationId = "com.willingtocode.voicepathfinder"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures { compose = true }

    testOptions {
        // Robolectric builds a working Context from this module's manifest, and fails
        // with a missing-manifest error without this.
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // The same icon set the main app uses for its navigation bar.
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json, the implementation the app ships against, so the REST parsing is
    // tested against the vendor's documented shapes rather than a stub.
    testImplementation("org.robolectric:robolectric:4.17")
    // Serves recorded responses to VoipMs, the same way the product app's tests do.
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")
}
