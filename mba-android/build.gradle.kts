plugins {
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.multiplatform)
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
        }
        androidMain.dependencies {
            implementation(libs.androidx.work.runtime)
            implementation(libs.androidx.startup)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
