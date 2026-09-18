import groovy.json.JsonSlurper
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

/**
 * The WEB client id (`client_type: 3`) out of one of the Firebase config files,
 * or null.
 *
 * Google sign-in needs it, and it is the one value Google's own tooling does not
 * hand the app: `google-services.json` carries it, and the google-services
 * plugin generates a `default_web_client_id` string resource for it — but only
 * when the file has a type-3 client, which is exactly the state this project was
 * in before the Google provider was enabled in the Firebase console. Reading it
 * here means enabling the provider and re-downloading the file is the ONLY step
 * an operator has to take; nothing is hand-copied into gradle.properties, and a
 * build that predates the change simply gets an empty id (and a sign-in screen
 * that offers no Google button) instead of a crash at first tap.
 *
 * A lambda rather than a top-level function because this is a script, where the
 * declaration order of `fun`s is easy to get wrong and impossible to see.
 *
 * It is NOT a secret: it ships inside the app either way, and its only use is to
 * identify which OAuth project a sign-in is for.
 */
val webClientIdIn: (File) -> String? = { config ->
    if (!config.exists()) {
        null
    } else {
        runCatching {
            val root = JsonSlurper().parse(config) as Map<*, *>
            (root["client"] as? List<*>)
                ?.asSequence()
                ?.mapNotNull { it as? Map<*, *> }
                ?.flatMap { client ->
                    ((client["oauth_client"] as? List<*>) ?: emptyList<Any?>()).asSequence()
                }
                ?.mapNotNull { it as? Map<*, *> }
                ?.firstOrNull { (it["client_type"] as? Number)?.toInt() == 3 }
                ?.get("client_id") as? String
        }.getOrNull()
    }
}

// The release config first: the web client id is a property of the Firebase
// PROJECT, not of an app entry, so both files hold the same value and the
// variant-specific one is only a fallback for a checkout that has just the
// debug copy.
val googleWebClientId: String = (project.findProperty("googleWebClientId") as String?)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: webClientIdIn(file("google-services.json"))
    ?: webClientIdIn(file("src/debug/google-services.json"))
    ?: ""

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
        //
        // Both spellings are accepted because Gradle property names are
        // case-sensitive and the wrong one fails SILENTLY: `-PgoodpostBaseUrl=…`
        // (lower-case p, as the notes above and the property below were written)
        // is a different key from `goodPostBaseUrl`, so it set nothing, the empty
        // value from gradle.properties won, and the build reported success while
        // producing an app that says no backend is configured. Flagging a typo as
        // an error is not possible from here, so both are read instead.
        val goodPostBaseUrl = (
            (project.findProperty("goodPostBaseUrl") as String?)?.takeIf { it.isNotBlank() }
                ?: (project.findProperty("goodpostBaseUrl") as String?)
            ).orEmpty().trim().trimEnd('/')
        buildConfigField("String", "GOODPOST_BASE_URL", "\"$goodPostBaseUrl\"")

        // There is deliberately no contact address configured here any more.
        // The sign-in screen used to ask readers to write to the deployment's
        // owner for channel access, which is now something the form on that
        // screen does by itself (§16) — and a private address printed on a
        // public screen is a liability rather than a feature. `goodPostContactEmail`
        // is therefore gone with the block that showed it; a support address
        // belongs in a Help row in Settings, where a reader looks for it.

        // The Google sign-in `serverClientId` (§16), read from the Firebase
        // config above rather than configured by hand. Empty is a supported
        // state and the reason the creator screen decides whether to offer the
        // button at all: with no id there is nothing to send Google, and a
        // button that can only fail is worse than no button.
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")

        // The floor the channel form states and checks before it submits
        // (§10). The SERVER enforces it either way; this is what lets the form
        // say "at least 8 characters" instead of refusing a password nobody was
        // told the rule for. Keep it equal to the backend's
        // ADMIN_MIN_PASSWORD_LENGTH — see gradle.properties.
        val adminMinPasswordLength = (project.findProperty("adminMinPasswordLength") as String?)
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: 8
        // `int`, not `Int`: buildConfigField emits a JAVA field, and `Int` is not
        // a Java type — it compiles to `public static final Int …` and every use
        // of it then fails with "Cannot access class 'Int'".
        buildConfigField("int", "ADMIN_MIN_PASSWORD_LENGTH", "$adminMinPasswordLength")
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
            // Note which Firebase Android app this selects. The debug variant
            // reads `app/src/debug/google-services.json`, which registers BOTH
            // package names; the release variant reads `app/google-services.json`,
            // which registers only `com.muddassir.clearview`. So the debug
            // SHA-1/SHA-256 belong on the `.debug` entry in the Firebase console
            // and the release certificate belongs on the production entry —
            // Firebase matches on package name FIRST, then checks the cert.
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
    // ExoPlayer, for the Good Post video player (§9). This app's other player is a
    // bespoke android.media.MediaPlayer implementation bound to the Media tab's
    // own `MediaVideo` model, so it can't play a channel's presigned bucket URL
    // without dragging that model — and YouTube/Instagram resolution — into a feed.
    // `media3-ui` supplies the surface (PlayerView); the controls are Good Post's
    // own, in Compose.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

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
    //
    // This is the one Firebase dependency the app has, which is why
    // `google-services.json` stays: the plugin is what initialises both of them
    // from it at startup. The debug file registers `com.muddassir.clearview.debug`
    // as well as the production id, so a debug install reports to its own app
    // entry instead of polluting production analytics.
    //
    // Analytics: the behavioural data, and its app-entry split above.
    implementation("com.google.firebase:firebase-analytics")
    //
    // Auth, and ONLY for identity (§3, §16). A reader is signed in ANONYMALLY at
    // first use, which gives the app a uid to key its own state off — follows,
    // read positions — without ever asking for an email, a password or a phone
    // number. A channel creator signs in with email + password on the SAME
    // Firebase, and the two are told apart by the token's provider claim.
    //
    // What is deliberately NOT here: Cloud Messaging. There is no push
    // infrastructure behind Good Post yet, and notifications are described in
    // §8 as a system to build rather than a library to add — adding the SDK now
    // would ship a service nothing sends to.
    implementation("com.google.firebase:firebase-auth")

    // Push (§8). The channel notifications arrive from the backend through FCM
    // instead of waiting for the fifteen-minute check — see
    // `goodpost/data/GoodPostPush.kt`. It rides the same Firebase project the
    // reader's anonymous identity already signs in to, so there is no second
    // project, no second google-services.json and no sender id to configure.
    implementation("com.google.firebase:firebase-messaging")

    // ── Google sign-in (§16) ──
    //
    // Credential Manager, not `play-services-auth`'s `GoogleSignInClient`.
    // Google deprecated that API in favour of this one, and it is the only one
    // that returns an ID token without the app also holding a Google account
    // session of its own — which is the whole requirement here, since the token
    // is exchanged for a Firebase credential and then discarded.
    //
    // `credentials` is the API, `credentials-play-services-auth` is the backend
    // that actually talks to Google on a device with Play services, and
    // `googleid` supplies the request/option types.
    implementation("androidx.credentials:credentials:1.2.0-rc01")
    implementation("androidx.credentials:credentials-play-services-auth:1.2.0-rc01")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.0")
}
