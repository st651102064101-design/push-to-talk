import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val pttVersionName = "1.2.2-docker"
val pttVersionCode = 10
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

val copyVersionedDebugApk by tasks.registering(Copy::class) {
    description = "Creates a debug APK copy whose filename includes the app version."
    from(layout.buildDirectory.dir("outputs/apk/debug")) {
        include("app-debug.apk")
        rename { "PTT-$pttVersionName-debug.apk" }
    }
    into(layout.buildDirectory.dir("outputs/apk/debug"))
}

val copyVersionedReleaseApk by tasks.registering(Copy::class) {
    description = "Creates a release APK copy whose filename includes the app version."
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("app-release.apk")
        rename { "PTT-$pttVersionName-release.apk" }
    }
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("app-release-unsigned.apk")
        rename { "PTT-$pttVersionName-release-unsigned.apk" }
    }
    into(layout.buildDirectory.dir("outputs/apk/release"))
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(copyVersionedDebugApk)
}
tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(copyVersionedReleaseApk)
}
