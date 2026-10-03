plugins {
    alias(libs.plugins.android.application)
}

val releaseVersion = providers.gradleProperty("releaseVersion").orElse("0.1.0-dev")
val releaseVersionCode = providers.gradleProperty("releaseVersionCode").orElse("1")
val releaseKeystore = providers.environmentVariable("ANDROID_KEYSTORE_PATH")

android {
    namespace = "app.narratify"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.narratify"
        minSdk = 26
        targetSdk = 37
        versionCode = releaseVersionCode.get().toInt().also {
            require(it in 1..2_100_000_000) { "releaseVersionCode must be between 1 and 2100000000" }
        }
        versionName = releaseVersion.get()
    }

    if (releaseKeystore.isPresent) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore.get())
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":shared:domain"))
    implementation(project(":shared:data"))
    implementation(project(":shared:text"))
    implementation(project(":shared:playback"))
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.readium.navigator.media.tts)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.onnxruntime.android)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit4)
    testImplementation("org.json:json:20240303")
}
