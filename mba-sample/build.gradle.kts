import java.util.Properties

fun String.escapedForBuildConfig(): String = replace("\\", "\\\\").replace("\"", "\\\"")

val localProps = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) localPropsFile.inputStream().use(::load)
}

fun propOrEnv(name: String): String? =
    localProps.getProperty(name) ?: System.getenv(name)

fun projectPropOrEnv(name: String): String? =
    providers.gradleProperty(name).orNull ?: propOrEnv(name)

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.sunnat629.mba.sample"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.sunnat629.mba.sample"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0-kotlinconf"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        val notionKey = projectPropOrEnv("NOTION_TOKEN") ?: localProps.getProperty("notion.api.key") ?: ""
        val crashDbId = projectPropOrEnv("NOTION_CRASH_DB_ID_OR_URL") ?: ""
        val ticketDbId = projectPropOrEnv("NOTION_TICKET_DB_ID_OR_URL") ?: ""
        val geminiKey = projectPropOrEnv("GEMINI_API_KEY") ?: ""
        val serverApiKey = projectPropOrEnv("MBA_SERVER_API_KEY")
            ?: ""
        val backendEndpoint = projectPropOrEnv("MBA_SAMPLE_BACKEND_ENDPOINT")
            ?: "http://10.0.2.2:8080"
        val sampleMode = projectPropOrEnv("MBA_SAMPLE_MODE")
            ?: "sdkOnly"
        val sampleUseAgent = projectPropOrEnv("MBA_SAMPLE_USE_AGENT")
            ?: "true"
        val githubToken = projectPropOrEnv("GITHUB_TOKEN")
            ?: ""
        val githubOwner = projectPropOrEnv("GITHUB_OWNER")
            ?: projectPropOrEnv("GITHUB_TARGET_OWNER")
            ?: ""
        val githubRepo = projectPropOrEnv("GITHUB_REPO")
            ?: projectPropOrEnv("GITHUB_TARGET_REPO")
            ?: ""

        buildConfigField("String", "NOTION_API_KEY", "\"${notionKey.escapedForBuildConfig()}\"")
        buildConfigField("String", "NOTION_CRASH_DB_ID", "\"${crashDbId.escapedForBuildConfig()}\"")
        buildConfigField("String", "NOTION_TICKET_DB_ID", "\"${ticketDbId.escapedForBuildConfig()}\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"${geminiKey.escapedForBuildConfig()}\"")
        buildConfigField("String", "MBA_SERVER_API_KEY", "\"${serverApiKey.escapedForBuildConfig()}\"")
        buildConfigField("String", "MBA_BACKEND_ENDPOINT", "\"${backendEndpoint.escapedForBuildConfig()}\"")
        buildConfigField("String", "MBA_SAMPLE_MODE", "\"${sampleMode.escapedForBuildConfig()}\"")
        buildConfigField("String", "MBA_SAMPLE_USE_AGENT", "\"${sampleUseAgent.escapedForBuildConfig()}\"")
        buildConfigField("String", "GITHUB_TOKEN", "\"${githubToken.escapedForBuildConfig()}\"")
        buildConfigField("String", "GITHUB_OWNER", "\"${githubOwner.escapedForBuildConfig()}\"")
        buildConfigField("String", "GITHUB_REPO", "\"${githubRepo.escapedForBuildConfig()}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    implementation(libs.mba.android)
    implementation(libs.mba.notion)
    implementation(libs.mba.github)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.okhttp)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
