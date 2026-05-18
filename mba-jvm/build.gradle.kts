plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

apply(from = rootProject.file("gradle/publishing.gradle.kts"))

kotlin {
    explicitApi()
}

dependencies {
    api(project(":mba-core"))
    api(project(":mba-agent"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}

afterEvaluate {
    extensions.configure<org.gradle.api.publish.PublishingExtension>("publishing") {
        publications {
            create<org.gradle.api.publish.maven.MavenPublication>("jvm") {
                from(components["java"])
                artifactId = "mba-jvm"
            }
        }
    }
}
