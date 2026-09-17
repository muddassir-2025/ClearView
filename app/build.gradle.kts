import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
}

// Release signing credentials, read from keystore.properties (gitignored) so
// secrets never live in the repo. When the file is absent (fresh clone, CI,
// another machine), the release buildType simply has no signingConfig and
// produces an unsigned artifact; debug builds are unaffected. This keeps the
// project buildable everywhere while only THIS machine signs releases.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}
val hasReleaseSigning = keystorePropertiesFile.exists()

android {
    namespace = "com.muddassir.clearview"

    compileSdk = 37

    defaultConfig {
        applicationId = "com.muddassir.clearview"
        minSdk = 24
        targetSdk = 37
        versionCode = 22
        versionName = "10.12"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Good Post backend (§41). Deliberately EMPTY by default: a build
        // must not silently talk to a developer's machine, an expired
        // Cloudflare tunnel, or a stale preview URL. Set it once in
        // gradle.properties (goodPostBaseUrl=https://…) or per build with
        // -PgoodPostBaseUrl=https://… . Until it is set, the Good Post tab
        // says so plainly instead of failing with a confusing network error.
        val goodPostBaseUrl = (project.findProperty("goodPostBaseUrl") as String?).orEmpty()
        buildConfigField("String", "GOODPOST_BASE_URL", "\"$goodPostBaseUrl\"")

        // The address the sign-in screen shows under "Don't have channel access?"
        // (§16). Configured here for the same reason as the base URL: it belongs
        // to whoever deploys this backend, so baking one into the source would be
        // wrong everywhere but one installation. Empty is a valid state — the
        // screen falls back to its placeholder rather than showing nothing.
        val goodPostContactEmail = (project.findProperty("goodPostContactEmail") as String?).orEmpty()
        buildConfigField("String", "GOODPOST_CONTACT_EMAIL", "\"$goodPostContactEmail\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Install side-by-side with the Play Store release. The debug APK is
            // signed with the debug keystore, so Android refuses to install it
            // over the release app (same applicationId, different signature). A
            // ".debug" suffix gives the debug build its own applicationId
            // (com.muddassir.clearview.debug) — a separate app that coexists
            // with the Play Store one, each with its own data.
            //
            // Note which Firebase Android app this selects: the debug build
            // registers as ".debug", so the debug SHA-1/SHA-256 belong on THAT
            // entry in the Firebase console, not on com.muddassir.clearview.
            // Firebase matches on package name first, then checks the cert.
            //
            // Deleting this line instead makes the debug build identify as
            // com.muddassir.clearview, matching the Play Store id — needed to
            // exercise the production Firebase Android app. That trades away
            // the side-by-side install (INSTALL_FAILED_UPDATE_INCOMPATIBLE)
            // and moves the debug SHA requirement to the production app.
            applicationIdSuffix = ".debug"
        }
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        // java.time.chrono.HijrahDate (Umm al-Qura Islamic calendar) needs core
        // library desugaring — java.time only arrived natively in API 26 and
        // minSdk is 24.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        // BuildConfig.APPLICATION_ID — needed by the instrumentation test to
        // assert the variant's real applicationId (the debug build carries a
        // ".debug" suffix).
        buildConfig = true
    }

    testOptions {
        // JVM unit tests run against the stubbed android.jar. Returning default
        // values lets defensive code that calls e.g. android.util.Log.w inside a
        // catch block keep working in tests instead of throwing "Method ... not
        // mocked". Behaviour in the real app is unchanged.
        unitTests.isReturnDefaultValues = true
    }

}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
   implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // On-device YouTube audio extraction (NewPipeExtractor, GPL-3.0-or-later):
    // resolves the direct audio-stream URL from the phone's own IP so downloads
    // never depend on a server. Audio-only streams only — no FFmpeg needed.
    implementation(libs.newpipe.extractor)
    // MediaSessionCompat + MediaStyle notification for the offline-audio
    // foreground service: background playback with lock-screen / notification
    // media controls.
    implementation(libs.androidx.media)

    testImplementation(libs.junit)
    // org.json is stubbed in the Android SDK; provide the real JVM impl for unit tests
    testImplementation(libs.org.json)

    // Core library desugaring (compile-time backport of java.time for API 24/25).
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // The Compose BOM must ALSO be on the androidTest + debug classpaths. The
    // versionless ui-test-junit4 / ui-test-manifest artifacts are only resolved
    // through BOM constraints; without the platform here, lint (which builds an
    // androidTest model) fails with "Could not find androidx.compose.ui:ui-test-junit4:".
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(platform("com.google.firebase:firebase-bom:34.18.0"))
    // Analytics only. Good Post deliberately does NOT bring in Firebase Auth or
    // Cloud Messaging any more: its readers have no accounts to authenticate and
    // no inbox to push to, so both dependencies were an unused surface that only
    // widened what the app asks for at install time. Administrators sign in
    // against the ClearView backend, and channel notifications are a device-local
    // preference until real push infrastructure exists (§14).
    implementation("com.google.firebase:firebase-analytics")
}
