plugins {
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()

    android {
        namespace = "dev.sunnat629.mba.android"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":mba-core"))
            implementation(project(":mba-notion"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.work.runtime)
            implementation(libs.androidx.startup)
            implementation(libs.ktor.client.okhttp)
        }
    }
}
