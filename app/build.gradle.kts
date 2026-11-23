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

// Built-in emoji font, copied into the APK as assets/emoji_font.ttf.
// Default: fonts/NotoColorEmoji.ttf (Google Noto Color Emoji, SIL OFL 1.1).
// Override per machine with `emoji.font=<path to .ttf>` in <root>/local.properties (not in git).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val emojiFontSource: File? = localProps.getProperty("emoji.font")?.trim()?.takeIf { it.isNotEmpty() }?.let { file(it) }
    ?: rootProject.file("fonts/NotoColorEmoji.ttf").takeIf { it.isFile }
val emojiFontAssets = layout.buildDirectory.dir("generated/emojiFont/assets")

android {
    namespace = "com.ssonkoict.keyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ssonkoict.keyboard"
        minSdk = 24
        targetSdk = 35
        versionCode = 13
        versionName = "0.13.0"
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

    sourceSets["main"].assets.srcDir(emojiFontAssets)

    androidResources {
        // Kept uncompressed so the keyboard can memory-map the font instead of
        // unpacking hundreds of MB into RAM.
        noCompress += "ttf"
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

// Copies the emoji font from local.properties into the APK (or removes it if unset).
val prepareEmojiFont by tasks.registering {
    val src = emojiFontSource
    val outDir = emojiFontAssets.get().asFile
    inputs.property("emojiFont", src?.path ?: "")
    if (src != null && src.isFile) inputs.file(src)
    outputs.dir(outDir)
    doLast {
        val dest = File(outDir, "emoji_font.ttf")
        outDir.mkdirs()
        if (src == null) {
            dest.delete()
        } else {
            if (!src.isFile) throw GradleException("emoji.font in local.properties points to a missing file: $src")
            if (!dest.isFile || dest.length() != src.length() || dest.lastModified() < src.lastModified()) {
                src.copyTo(dest, overwrite = true)
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(buildRust, prepareEmojiFont)
}
