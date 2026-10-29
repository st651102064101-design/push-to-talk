import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val repositoryRoot = rootProject.projectDir.parentFile.parentFile
val flutterProperties = Properties().apply {
    rootProject.file("local.properties").inputStream().use(::load)
}
val pttVersionName = flutterProperties.getProperty("flutter.versionName", "dev")
val releasePropertiesFile = File(repositoryRoot, "keystore.properties")
val releaseProperties = Properties().apply {
    if (releasePropertiesFile.isFile) {
        releasePropertiesFile.inputStream().use(::load)
    }
}

android {
    namespace = "com.example.ptt_flutter"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "com.example.ptt_flutter"
        minSdk = 34
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    signingConfigs {
        if (releasePropertiesFile.isFile) {
            create("release") {
                val configuredStoreFile = File(repositoryRoot, releaseProperties.getProperty("storeFile"))
                require(configuredStoreFile.isFile) {
                    "Release keystore was not found: $configuredStoreFile"
                }
                storeFile = configuredStoreFile
                storePassword = releaseProperties.getProperty("storePassword")
                keyAlias = releaseProperties.getProperty("keyAlias")
                keyPassword = releaseProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            if (releasePropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    doLast {
        val sourceApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        if (sourceApk.isFile) {
            val outputDir = layout.buildDirectory.dir("outputs/versioned-apk/debug").get().asFile
            outputDir.mkdirs()
            sourceApk.copyTo(outputDir.resolve("PTT-Flutter-$pttVersionName-debug.apk"), overwrite = true)
        }
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    doLast {
        val releaseDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val signedApk = releaseDir.resolve("app-release.apk")
        val unsignedApk = releaseDir.resolve("app-release-unsigned.apk")
        val sourceApk = if (signedApk.isFile) signedApk else unsignedApk
        if (sourceApk.isFile) {
            val outputDir = layout.buildDirectory.dir("outputs/versioned-apk/release").get().asFile
            outputDir.mkdirs()
            val suffix = if (sourceApk == signedApk) "release" else "release-unsigned"
            sourceApk.copyTo(outputDir.resolve("PTT-Flutter-$pttVersionName-$suffix.apk"), overwrite = true)
        }
    }
}
