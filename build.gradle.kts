buildscript {
    dependencies {
        // Keep AGP's built-in Kotlin and the Compose compiler on the same version.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
