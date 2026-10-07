import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseKeystorePropertiesFile = rootProject.file("keystore.properties")
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.isFile) {
        releaseKeystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun releaseSigningProperty(name: String): String =
    releaseKeystoreProperties.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: throw GradleException("Missing '$name' in keystore.properties; see docs/release-signing.md")

val validateReleaseSigning by tasks.registering {
    group = "verification"
    description = "Require local signing configuration before building a release."
    doLast {
        if (!releaseKeystorePropertiesFile.isFile) {
            throw GradleException("Release signing requires keystore.properties; see docs/release-signing.md")
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseSigning)
}

android {
    namespace = "io.github.micro123.mediaplayer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.micro123.mediaplayer"
        minSdk = 26
        targetSdk = 37
        versionCode = providers.gradleProperty("appVersionCode").get().toInt()
        versionName = providers.gradleProperty("appVersionName").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystorePropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file(releaseSigningProperty("storeFile")).also {
                    if (!it.isFile) {
                        throw GradleException("Release keystore does not exist; see docs/release-signing.md")
                    }
                }
                storeType = releaseKeystoreProperties.getProperty("storeType", "PKCS12")
                storePassword = releaseSigningProperty("storePassword")
                keyAlias = releaseSigningProperty("keyAlias")
                keyPassword = releaseSigningProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:player"))
    implementation(libs.smbj)
    implementation(libs.bouncycastle)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    testImplementation("junit:junit:4.13.2")
}
