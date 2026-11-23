import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: values live in <root>/keystore.properties (not in git).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.ssonkoict.keyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ssonkoict.keyboard"
        minSdk = 24
        targetSdk = 35
        versionCode = 12
        versionName = "0.12.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1") // FileProvider, NotificationManagerCompat
}

// Build the Rust core for each Android ABI and drop the .so files into jniLibs.
// Needs: rustup targets + cargo-ndk + Android NDK (see README.md).
val buildRust by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds keycore (Rust) for Android with cargo-ndk"
    workingDir = rootProject.file("keycore")
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
        "-o", file("src/main/jniLibs").absolutePath,
        "build", "--release",
    )
}

tasks.named("preBuild") {
    dependsOn(buildRust)
}
