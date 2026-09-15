import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** The one application module's baseline. Same SDK and JVM levels as the libraries, plus Compose. */
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    compileSdk = 36
    defaultConfig {
        minSdk = 29
        targetSdk = 36
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
