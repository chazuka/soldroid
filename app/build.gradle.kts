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

android {
    namespace = "id.ocbc.chatty"

    defaultConfig {
        applicationId = "id.ocbc.chatty"
        versionCode = 1
        versionName = "0.1.0"

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

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // A demo has no release keystore, and an unsigned release APK cannot be installed on the
            // handset it is meant to be shown on. Signing with the debug key keeps `assembleRelease`
            // runnable — and therefore keeps R8 honest, since shrinking only breaks in release —
            // while making it obvious this is not a distributable build.
            signingConfig = signingConfigs.getByName("debug")
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
