/**
 * Convention plugin for JVM-only library modules (mba-jvm, mba-server).
 *
 * Applies:
 * - kotlin.jvm + kotlin.serialization
 * - explicitApi() enforcement
 * - Test dependencies
 *
 * Usage in module build.gradle.kts:
 * ```
 * plugins {
 *     id("mba.jvm.library")
 * }
 * ```
 */

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    explicitApi()
}

dependencies {
    testImplementation(kotlin("test"))
}
