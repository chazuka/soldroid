plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

/**
 * Generates the app's Baseline Profile. Not shipped: this is a `com.android.test` module that drives
 * the real app through its cold-start journey on a device and writes the resulting profile back into
 * `:app`, where `profileinstaller` installs it on first run.
 */
android {
    namespace = "id.ocbc.chatty.baselineprofile"
    compileSdk = 36
    defaultConfig {
        // The generator drives the app through UI Automator, which needs a recent enough platform.
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    targetProjectPath = ":app"
}

baselineProfile {
    // One profile for the whole app rather than per variant: there is one variant that matters.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
