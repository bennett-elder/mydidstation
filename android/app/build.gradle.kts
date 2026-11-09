import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.willingtocode.mydidstation"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.willingtocode.mydidstation"
        minSdk = 24
        targetSdk = 36
        // Version comes from android/version.properties, which is committed so
        // F-Droid builds the same numbers a clean checkout produces. CI overrides
        // it with -P for rolling builds; see scripts/version.sh.
        val versionProps = Properties().apply {
            val f = rootProject.file("version.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull()
            ?: versionProps.getProperty("VERSION_CODE")?.trim()?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?)
            ?: versionProps.getProperty("VERSION_NAME")?.trim() ?: "0.1.0"
    }

    buildFeatures { compose = true }

    testOptions {
        // Robolectric builds a working Context from this module's manifest and
        // resources, and fails with a missing-manifest error without this.
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Release signing comes from the environment, so CI can supply a keystore
    // while a plain local build still works. Set all four variables to get a
    // signed release APK; leave them unset and the release build stays unsigned.
    val ksPath = System.getenv("RELEASE_KEYSTORE_PATH")
    val ksStorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
    val ksAlias = System.getenv("RELEASE_KEY_ALIAS")
    val ksKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
    val haveReleaseKey =
        !ksPath.isNullOrBlank() && !ksStorePassword.isNullOrBlank() &&
            !ksAlias.isNullOrBlank() && !ksKeyPassword.isNullOrBlank()

    signingConfigs {
        if (haveReleaseKey) {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = ksStorePassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (haveReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Three glyphs for the bottom bar. The extended set is tens of megabytes; core is not.
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    // Decoding and displaying a file this app downloaded itself. Deliberately not
    // coil-network-okhttp: Coil never needs a network client here, because MediaFetcher owns
    // every rule about what may be fetched and what may be kept. That keeps the library's
    // blast radius to a decoder fed only bytes already capped and identified.
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Real SQLite and real ContentValues on the JVM, so Store can be tested
    // without a device and without changing how it is written.
    testImplementation("org.robolectric:robolectric:4.17")
    // Serves recorded API responses to VoipMs, so parsing is tested against the
    // shapes the vendor documents rather than against a stub written to agree
    // with the code.
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")

    // A tap, checked on the JVM rather than on an emulator. Twice a screen has shipped
    // with no click handler on its obvious control, and calling functions in a test cannot
    // see that, because the missing thing is the wiring. The manifest artifact supplies the
    // host activity the Compose rule starts, and it has to be a debug dependency: Robolectric
    // reads the debug app manifest, not the unit test one. Robolectric supplies the rest.
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
