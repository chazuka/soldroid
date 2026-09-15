import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The Android library baseline: SDK levels, Java/Kotlin target, nothing else.
 *
 * It exists so `compileSdk`, `minSdk` and the JVM target are stated once. A module that also draws
 * UI applies `chatty.android.library.compose` on top of this one rather than repeating the block.
 */
plugins {
    id("com.android.library")
}

android {
    compileSdk = 36
    defaultConfig {
        // minSdk 29 (Android 10). The floor is LiveKit's, and 29 is also where cleartext HTTP is
        // off by default — which suits an app that only ever talks TLS to three vendors.
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
}
