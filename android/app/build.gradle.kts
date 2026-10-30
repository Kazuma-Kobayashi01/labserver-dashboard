import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.labserver.lablink"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.labserver.lablink"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.5.0"
        // labtunnel.aar (gomobile) ships these two ABIs: arm64 phones and the x86_64 emulator
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Release signing: keystore.properties (git-ignored; storeFile, storePassword, keyAlias, keyPassword) when it exists, else the
    // local Android debug key, so a release build can update an installed debug build in place (same signature).
    signingConfigs {
        create("release") {
            val props = Properties().apply {
                rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
            }
            storeFile = file(props.getProperty("storeFile") ?: "${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = props.getProperty("storePassword") ?: "android"
            keyAlias = props.getProperty("keyAlias") ?: "androiddebugkey"
            keyPassword = props.getProperty("keyPassword") ?: "android"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // libwstunnel.so is an executable: it must be extracted to nativeLibraryDir
    packaging { jniLibs.useLegacyPackaging = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(files("libs/labtunnel.aar"))   // built by ../tunnel/build-aar.sh

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.backdrop)
    implementation(libs.termlib)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // the android.jar one is a stub on the JVM
    testImplementation(libs.kotlinx.coroutines.android)
}

// the Compose mapping file is only for deobfuscating stack traces; its helper artifact is not available offline
composeCompiler { includeComposeMappingFile.set(false) }
