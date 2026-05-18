import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.serialization)
}

apply(from = rootProject.file("gradle/publishing.gradle.kts"))

android {
    namespace = "dev.sunnat629.mba.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    extensions.configure<org.gradle.api.publish.PublishingExtension>("publishing") {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "mba-android"
            }
        }
    }
}

dependencies {
    api(project(":mba-core"))
    api(project(":mba-agent"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.startup)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.ktor.client.mock)
}
