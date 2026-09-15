plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "app.narratify.shared.align"
        compileSdk = 37
        minSdk = 26
        withHostTestBuilder {}
    }

    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:domain"))
            implementation(project(":shared:text"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        // Reads the alignment fixtures from disk, which common code cannot do.
        jvmTest.dependencies {
            implementation(project(":shared:domain"))
            implementation(project(":shared:text"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
