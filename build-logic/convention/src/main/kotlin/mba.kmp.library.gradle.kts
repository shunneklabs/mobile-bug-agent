/**
 * Convention plugin for KMP library modules (mba-core, mba-agent, mba-notion, mba-android).
 *
 * Applies:
 * - android.kmp.library + kotlin.multiplatform + kotlin.serialization
 * - Shared compileSdk, minSdk, JVM target
 * - explicitApi() enforcement
 * - commonTest source set with kotlin-test
 *
 * Usage in module build.gradle.kts:
 * ```
 * plugins {
 *     id("mba.kmp.library")
 * }
 * ```
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    explicitApi()

    android {
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Access the version catalog
val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()
