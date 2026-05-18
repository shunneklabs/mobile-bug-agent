plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

apply(from = rootProject.file("gradle/publishing.gradle.kts"))

kotlin {
    explicitApi()

    wasmJs {
        browser()
    }
    js {
        browser()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
