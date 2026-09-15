pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // LiveKit's `audioswitch` transitive is published on JitPack, not Maven Central. Scoped to
        // that one group: JitPack serves arbitrary GitHub builds, and an unscoped entry would let it
        // answer for any coordinate a typo produces.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.davidliu") }
        }
    }
}

rootProject.name = "chatty"

include(":app")

// Generates the app's Baseline Profile against a connected device. Not shipped.
include(":baselineprofile")

// Plain JVM. The chat client (kamartaj), the speech synthesizer (ElevenLabs) and the conversation
// state machine. No Android, so all of it is unit-testable without a device.
include(":core:ai")

// Android. LiveAvatar LITE session + the LiveKit subscriber that renders the face.
include(":core:avatar")

// Android + Compose. Theme tokens and the handful of shared composables.
include(":core:ui")
