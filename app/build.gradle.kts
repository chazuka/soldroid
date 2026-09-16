import java.io.File

plugins {
    id("chatty.android.application")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.androidx.baselineprofile)
}

/**
 * The whole of the app's configuration, read from the repo-root `.env` at build time.
 *
 * Read through Gradle's `providers` so the configuration cache treats the file as a declared input:
 * editing `.env` re-runs configuration, and nothing has to be told to.
 *
 * A key may also come from the process environment, which is what CI uses — there is no `.env` on a
 * build agent, and there should not be one.
 */
val dotenv: Map<String, String> = providers
    .fileContents(layout.settingsDirectory.file(".env"))
    .asText.getOrElse("")
    .lineSequence()
    .map(String::trim)
    .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
    .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().trim('"') }

fun secret(name: String): String =
    dotenv[name]?.takeIf(String::isNotEmpty)
        ?: providers.environmentVariable(name).getOrElse("")

/**
 * The keystore the release build is signed with, or `null` when this machine does not have one.
 *
 * `KEYSTORE_PATH` may be absolute or relative to the repo root, so a build agent can point at a
 * keystore it decoded into a temp directory while a laptop points at one next to the checkout.
 */
val releaseKeystore: File? = secret("KEYSTORE_PATH")
    .takeIf(String::isNotEmpty)
    ?.let { path -> File(path).takeIf(File::isAbsolute) ?: rootProject.file(path) }
    ?.takeIf(File::exists)

android {
    namespace = "id.ocbc.chatty"

    defaultConfig {
        // The install identity, deliberately not the source package. `namespace` above stays
        // `id.ocbc.chatty`, so R, BuildConfig and every Kotlin file keep their package; only the id
        // Android and the app stores know the app by changes. Changing it produces a *different*
        // app: an installed build under the old id is not upgraded, it sits alongside the new one.
        applicationId = "id.ocbc.sol"
        versionCode = 4
        versionName = "0.1.0-beta"

        // These are demo credentials in a demo app, and an APK is not a secret store: anyone holding
        // the file can read them back out. That is the accepted cost of having no backend — the
        // moment this app is anything but a demo, the two vendor keys move behind a service that
        // mints short-lived tokens, and only that service's URL ships here.
        buildConfigField("String", "CHATTY_API_KEY", "\"${secret("CHATTY_API_KEY")}\"")
        buildConfigField("String", "LIVEAVATAR_API_KEY", "\"${secret("LIVEAVATAR_API_KEY")}\"")
        buildConfigField("String", "ELEVENLABS_API_KEY", "\"${secret("ELEVENLABS_API_KEY")}\"")
        buildConfigField("String", "CHATTY_BASE_URL", "\"https://kamartaj.xyz\"")
        // Optional: absent keys leave their model out of the chooser rather than failing the build,
        // so the app still runs for anyone who only has the demo API's key.
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${secret("ANTHROPIC_API_KEY")}\"")
        buildConfigField("String", "OPENAI_API_KEY", "\"${secret("OPENAI_API_KEY")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // Only declared when the keystore is actually present: an absent-file signing config fails
        // the whole configuration phase, which would break `assembleDebug` for anyone who has no
        // keystore and does not need one.
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = secret("KEYSTORE_PASSWORD")
                keyAlias = secret("KEY_ALIAS")
                keyPassword = secret("KEY_PASSWORD").ifEmpty { secret("KEYSTORE_PASSWORD") }

                // minSdk is 29, so every device that can install this APK verifies v3, and v3 is
                // what lets the key be rotated later without breaking upgrade installs. v1 (JAR
                // signing) is left off deliberately: it is slow to verify, it is the scheme the
                // Janus/Master Key class of bugs attacked, and nothing here can run on a device old
                // enough to need it. AGP emits only the schemes minSdk requires, so the v2 flag is
                // a no-op today and correctness insurance if minSdk ever drops below 28.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // A separate install identity, so a debug build and a signed beta can sit on the same
            // handset at once. Without it both are `id.ocbc.sol` signed by different keys, and
            // Android rejects the second install with INSTALL_FAILED_UPDATE_INCOMPATIBLE — which
            // shows up as Android Studio's Run silently failing right after the build succeeds.
            //
            // The suffix touches the applicationId only. `namespace` is unchanged, so R and
            // BuildConfig keep their package, and `:baselineprofile` still targets the release id
            // because it drives the nonMinifiedRelease variant rather than this one.
            applicationIdSuffix = ".debug"
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // Handsets only. LiveKit's WebRTC library ships a ~12-16MB native binary per ABI, and
            // the two x86 variants exist for emulators — which never receive a distributed APK.
            // Dropping them halves the download a tester pays for.
            //
            // Scoped to `release` on purpose: debug builds keep every ABI, so an emulator remains a
            // working development target. If a release build ever has to run on one, build the
            // debug variant instead of widening this.
            ndk {
                abiFilters += setOf("arm64-v8a", "armeabi-v7a")
            }

            // Beta builds are sideloaded, not uploaded to Play, so the only thing the key has to
            // do is stay the same between releases: Android refuses to upgrade an installed app
            // whose signature changed, and the tester would have to uninstall and lose their data.
            //
            // Falling back to the debug key when no keystore is configured keeps `assembleRelease`
            // runnable — and therefore keeps R8 honest, since shrinking only ever breaks in release
            // — but such an APK must not be handed to a tester, hence the warning.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug").also {
                    logger.warn(
                        "KEYSTORE_PATH is unset or missing: signing the release build with the " +
                            "debug key. This APK is for local checks only — do not distribute it, " +
                            "because the next build signed with the real key cannot upgrade it."
                    )
                }
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(project(":core:ai"))
    implementation(project(":core:avatar"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    // Installs the baseline profile on first run, so a cold start is AOT-compiled rather than
    // interpreted through the first frame.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.animation)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)

    // Consumes the profile produced by the :baselineprofile module.
    baselineProfile(project(":baselineprofile"))
}
