plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Plain JVM on purpose. Everything that decides *what* the companion says — which agent, which
// history, which voice — lives here and is testable with `./gradlew :core:ai:test`, no emulator.
// Adding an Android dependency to this module is the change that would end that, so don't.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    // Test-only, and never shipped. One test walks TurnTrace's properties so that a mark added and
    // forgotten fails the build rather than going quietly missing from the telemetry for weeks,
    // which is exactly what happened to three of them.
    testImplementation(kotlin("reflect"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
