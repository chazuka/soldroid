/**
 * Compose on top of [chatty.android.library]: the compiler plugin, the BOM, and the artifacts every
 * UI module needs. Applying the BOM here is what keeps the Compose artifacts on one aligned set of
 * versions without any module naming a Compose version at all.
 */
plugins {
    id("chatty.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    buildFeatures {
        compose = true
    }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    val bom = platform(libs.findLibrary("compose-bom").get())
    add("implementation", bom)
    add("androidTestImplementation", bom)
    add("implementation", libs.findLibrary("compose-ui").get())
    add("implementation", libs.findLibrary("compose-foundation").get())
    add("implementation", libs.findLibrary("compose-material3").get())
    add("implementation", libs.findLibrary("compose-ui-tooling-preview").get())
    add("debugImplementation", libs.findLibrary("compose-ui-tooling").get())
}
