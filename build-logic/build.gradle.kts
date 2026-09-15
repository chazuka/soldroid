import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

// These plugins compile against JDK 21 because JDK 21 is what they configure downstream.
// `kotlin-dsl` refuses to build when its Java and Kotlin targets disagree, so both move together.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    // Makes the AGP / Kotlin / KSP / Hilt Gradle plugins resolvable so the precompiled script
    // plugins can apply them by id.
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.compose.gradlePlugin)
    implementation(libs.kotlin.serialization.gradlePlugin)
    implementation(libs.ksp.gradlePlugin)
    implementation(libs.hilt.gradlePlugin)
}
