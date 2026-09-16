plugins {
    id("chatty.android.library.compose")
}

android {
    namespace = "id.ocbc.chatty.core.ui"
}

dependencies {
    // WindowCompat, for flipping the status-bar icons with the theme.
    implementation(libs.androidx.core)
    api(libs.compose.material.icons.extended)

    // The palette's contrast ratios are arithmetic over colour values, so they are a plain JVM test
    // — no emulator, no Robolectric. See `ContrastTest` for what is being asserted and why.
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
