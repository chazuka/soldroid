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
}
