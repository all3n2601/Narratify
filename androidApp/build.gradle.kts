plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.narratify"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.narratify"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-dev"
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
