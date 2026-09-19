plugins {
    id("chatty.android.library.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "id.ocbc.chatty.core.avatar"
}

dependencies {
    implementation(project(":core:ai"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    api(libs.livekit)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
