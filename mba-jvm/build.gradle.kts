plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":mba-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
