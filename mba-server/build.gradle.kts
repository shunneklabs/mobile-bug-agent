
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

val localProps = Properties()
rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { localProps.load(it) }

application {
    mainClass.set("dev.sunnat629.mba.server.ApplicationKt")
}

tasks.named<JavaExec>("run") {
    environment("GEMINI_API_KEY", localProps.getProperty("GEMINI_API_KEY", ""))
    environment("NOTION_API_KEY", localProps.getProperty("NOTION_TOKEN", ""))
    environment("NOTION_DATABASE_ID", localProps.getProperty("NOTION_CRASH_DB_ID_OR_URL", ""))
    environment("MBA_SERVER_API_KEY", localProps.getProperty("MBA_SERVER_API_KEY", ""))
    // GitHub auto-fix path (optional — only required when a client sends autoFix=true)
    environment("GITHUB_TOKEN", localProps.getProperty("GITHUB_TOKEN", ""))
    environment("GITHUB_OWNER", localProps.getProperty("GITHUB_OWNER", localProps.getProperty("GITHUB_TARGET_OWNER", "")))
    environment("GITHUB_REPO", localProps.getProperty("GITHUB_REPO", localProps.getProperty("GITHUB_TARGET_REPO", "")))
    environment("GITHUB_TARGET_OWNER", localProps.getProperty("GITHUB_TARGET_OWNER", ""))
    environment("GITHUB_TARGET_REPO", localProps.getProperty("GITHUB_TARGET_REPO", ""))
    environment("GITHUB_BASE_BRANCH", localProps.getProperty("GITHUB_BASE_BRANCH", "main"))
}

dependencies {
    implementation(project(":mba-core"))
    implementation(project(":mba-agent"))
    implementation(project(":mba-notion"))
    implementation(project(":mba-github"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.serialization.json)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
