import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val pttVersionName = "1.2.3-docker"
val pttVersionCode = 11
val releasePropertiesFile = rootProject.file("keystore.properties")
val releaseProperties = Properties().apply {
    if (releasePropertiesFile.exists()) {
        releasePropertiesFile.inputStream().use(::load)
    }
}

android {
    namespace = "com.example.pushtotalk"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.pushtotalk"
        minSdk = 34
        targetSdk = 35
        versionCode = pttVersionCode
        versionName = pttVersionName
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (releasePropertiesFile.exists()) {
            create("release") {
                val configuredStoreFile = rootProject.file(releaseProperties.getProperty("storeFile"))
                require(configuredStoreFile.isFile) { "Release keystore was not found: $configuredStoreFile" }
                storeFile = configuredStoreFile
                storePassword = releaseProperties.getProperty("storePassword")
                keyAlias = releaseProperties.getProperty("keyAlias")
                keyPassword = releaseProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            if (releasePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    buildFeatures {
        buildConfig = true
    }
}
kotlin {
    jvmToolchain(17)
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    doLast {
        val sourceApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        if (sourceApk.isFile) {
            val outputDir = layout.buildDirectory.dir("outputs/versioned-apk/debug").get().asFile
            outputDir.mkdirs()
            sourceApk.copyTo(outputDir.resolve("PTT-$pttVersionName-debug.apk"), overwrite = true)
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
            sourceApk.copyTo(outputDir.resolve("PTT-$pttVersionName-$suffix.apk"), overwrite = true)
        }
    }
}
