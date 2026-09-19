import java.io.File

plugins {
    id("chatty.android.application")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.sentry.android)
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
 * Runs a git command in the repo, or returns `null` when git cannot answer.
 *
 * Every call goes through `providers.exec` so the configuration cache records it as an input: the
 * version is re-derived when the repository moves, and stays cached when it has not. `null` covers
 * the cases that are not failures — a source archive with no `.git`, a shallow clone with no tags,
 * git not installed on the machine.
 */
fun git(vararg args: String): String? {
    val output = providers.exec {
        commandLine(listOf("git", *args))
        workingDir = rootDir
        isIgnoreExitValue = true
    }
    if (output.result.get().exitValue != 0) return null
    return output.standardOutput.asText.get().trim().ifEmpty { null }
}

private val releaseTag: String? = git("describe", "--tags", "--abbrev=0")
private val commitsSinceTag: Int = releaseTag?.let { git("rev-list", "--count", "$it..HEAD") }?.toIntOrNull() ?: 0
private val headSha: String? = git("rev-parse", "--short=7", "HEAD")
private val treeIsDirty: Boolean = git("status", "--porcelain") != null

/**
 * What the app calls itself, derived from the release tag rather than typed into this file.
 *
 * The convention is one annotated tag per artifact that leaves the machine, named `v` plus the
 * version: `v0.1.0-alpha.1`, `v0.1.0-beta.2`, `v0.1.0`. The tag is the record of what was built; the
 * channel is a semver pre-release label, so version ordering already knows alpha precedes beta
 * precedes stable, and no build logic has to be taught the names.
 *
 * Built exactly on a clean tag, the name is the tag: `0.1.0-beta`. Anywhere else it says so —
 * `0.1.0-beta+2.g18a9cbc`, plus `.dirty` for uncommitted changes — so an APK from a work in progress
 * can never be mistaken for the release it came after. Override with `-PappVersionName=…` when a
 * build agent knows better than the checkout does.
 */
val appVersionName: String = (findProperty("appVersionName") as String?)
    ?: releaseTag?.removePrefix("v")?.let { tag ->
        when {
            commitsSinceTag == 0 && !treeIsDirty -> tag
            else -> buildString {
                append(tag)
                append("+").append(commitsSinceTag)
                headSha?.let { append(".g").append(it) }
                if (treeIsDirty) append(".dirty")
            }
        }
    }
    ?: "0.0.0-dev"

/**
 * The number Android actually compares on upgrade, taken as the commit count on the current branch.
 *
 * It only has to rise, and a commit count does that for free: it needs no file to edit, no counter
 * to remember, and it cannot go backwards on a branch that only gains commits. `versionName` carries
 * the meaning; this carries the ordering, and the two are deliberately not derived from each other.
 *
 * The fallback of `1` is for a checkout with no history at all, where any number would be a guess —
 * a build agent in that position passes `-PappVersionCode=…`, which also wins over git when set.
 */
val appVersionCode: Int = (findProperty("appVersionCode") as String?)?.toIntOrNull()
    ?: git("rev-list", "--count", "HEAD")?.toIntOrNull()
    ?: 1

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

/**
 * Readable stack traces for release crashes, and nothing else.
 *
 * # Why this plugin is here at all
 *
 * The release build is minified, so a crash arrives as obfuscated frames that name nothing. This
 * plugin's one job here is to upload R8's mapping file so those frames are readable again. The SDK
 * itself is wired by hand in `AppModule` and `ChattyApplication`.
 *
 * # Why almost everything it offers is switched off
 *
 * Left to its defaults — or installed by `sentry-wizard` — it would also write the DSN into the
 * manifest and auto-instrument OkHttp through bytecode. Both are wrong here, and quietly:
 *
 *  - Manifest DSN means the SDK starts from a ContentProvider, *before* `Application.onCreate` and
 *    therefore before [TelemetryPolicy] is applied. There would be a window governed by the SDK's
 *    own defaults, which is exactly what that policy exists to prevent.
 *  - Bytecode instrumentation would add OkHttp spans on top of the interceptor and event listener
 *    `AppModule` already installs: every request timed twice, and double the spans against quota.
 *
 * Uploading is off unless this build has a token, so a clone with no Sentry credentials — which is
 * every developer's and every CI job that is not publishing — builds release exactly as before.
 */
sentry {
    // Off: the SDK dependency is declared explicitly, and the DSN belongs in one explicit init.
    autoInstallation.enabled.set(false)

    // Off: AppModule already wires the OkHttp interceptor and event listener by hand.
    tracingInstrumentation.enabled.set(false)

    // Off, and the reason is not duplication. Source context uploads the source *code* to the
    // backend so it can be shown beside a stack trace. This is a bank's codebase.
    includeSourceContext.set(false)

    // The mapping file only travels when this build has everything needed to send it. All three
    // are required — a token alone fails the build at upload time, which is a confusing way to
    // discover that a slug is missing — so an incomplete set is treated as "not configured" and
    // says so once, rather than breaking a release build somebody was in the middle of cutting.
    val token = secret("SENTRY_AUTH_TOKEN")
    val slug = secret("SENTRY_ORG")
    val project = secret("SENTRY_PROJECT")
    val canUpload = token.isNotEmpty() && slug.isNotEmpty() && project.isNotEmpty()
    if (token.isNotEmpty() && !canUpload) {
        logger.warn(
            "SENTRY_AUTH_TOKEN is set but SENTRY_ORG or SENTRY_PROJECT is not: the R8 mapping " +
                "will not be uploaded, and release crashes will arrive obfuscated."
        )
    }
    includeProguardMapping.set(canUpload)
    autoUploadProguardMapping.set(canUpload)
    if (canUpload) {
        authToken.set(token)
        org.set(slug)
        projectName.set(project)
    }

    // Debug builds are not minified, so there is no mapping to upload and nothing to gain.
    ignoredBuildTypes.set(setOf("debug"))
}

android {
    namespace = "id.ocbc.chatty"

    defaultConfig {
        // The install identity, deliberately not the source package. `namespace` above stays
        // `id.ocbc.chatty`, so R, BuildConfig and every Kotlin file keep their package; only the id
        // Android and the app stores know the app by changes. Changing it produces a *different*
        // app: an installed build under the old id is not upgraded, it sits alongside the new one.
        applicationId = "id.ocbc.sol"
        versionCode = appVersionCode
        versionName = appVersionName

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
        // Telemetry is opt-in by configuration: no DSN means turns are written to logcat and go no
        // further. See TelemetryPolicy for what is sent when there is one.
        buildConfigField("String", "SENTRY_DSN", "\"${secret("SENTRY_DSN")}\"")
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

            // 64-bit handsets only. Native code is 81% of this APK — LiveKit's WebRTC binary alone
            // is 12MB per ABI — so which ABIs ship is by far the largest size decision available,
            // and everything else put together is rounding error beside it.
            //
            // Measured: dropping `armeabi-v7a` took the release APK from 23.75MB to 16.77MB, a
            // saving of 6.97MB or 29.4%. What it costs is 32-bit-only devices, and this app cannot
            // meet one: `minSdk` is 29, every handset shipped since 2019 is 64-bit, and a customer
            // demo runs on current hardware. A device that genuinely needed it would fail to
            // install rather than misbehave, which is the right way round for a failure nobody
            // expects to see.
            //
            // Scoped to `release` on purpose: debug builds keep every ABI, so an emulator remains a
            // working development target. If a release build ever has to run on one, build the
            // debug variant instead of widening this.
            ndk {
                abiFilters += setOf("arm64-v8a")
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

/**
 * Prints the version this checkout would build, for a release script that has to name the artifact.
 *
 * ```
 * $ ./gradlew -q :app:printVersion
 * 0.1.0-beta 21
 * $ cp app/build/outputs/apk/release/app-release.apk \
 *     "dist/ocbc-sol-$(./gradlew -q :app:printVersion | cut -d' ' -f1).apk"
 * ```
 */
tasks.register("printVersion") {
    group = "help"
    description = "Prints the derived versionName and versionCode."
    // Read at configuration time: a task body may not reach into the project once the
    // configuration cache is on.
    val name = appVersionName
    val code = appVersionCode
    doLast { println("$name $code") }
}

/**
 * Fails unless this checkout is exactly a clean release tag, so a distributable is never cut from a
 * tree that cannot be reproduced.
 *
 * Run it before handing an APK to anyone:
 *
 * ```
 * $ ./gradlew :app:verifyReleaseVersion :app:assembleRelease
 * ```
 *
 * Deliberately not wired into `assembleRelease` itself — building a release locally to check that R8
 * has not broken anything is routine, and should not require committing first.
 */
tasks.register("verifyReleaseVersion") {
    group = "verification"
    description = "Fails when the working tree is not exactly on a clean release tag."
    val name = appVersionName
    doLast {
        check(!name.contains('+')) {
            "Version is '$name': HEAD is not a clean release tag. Commit, then tag it " +
                "`git tag -a v<version> -m <version>`, or pass -PappVersionName= to override."
        }
    }
}


dependencies {
    implementation(libs.sentry.android)
    implementation(libs.sentry.okhttp)
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
