import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        val localProps = rootProject.file("local.properties")
        val props = Properties()
        if (localProps.exists()) props.load(localProps.inputStream())

        val notionKey = props.getProperty("NOTION_TOKEN") ?: props.getProperty("notion.api.key") ?: ""
        val crashDbId = props.getProperty("NOTION_CRASH_DB_ID_OR_URL") ?: ""
        val ticketDbId = props.getProperty("NOTION_TICKET_DB_ID_OR_URL") ?: ""
        val geminiKey = props.getProperty("GEMINI_API_KEY") ?: ""

        buildConfigField("String", "NOTION_API_KEY", "\"$notionKey\"")
        buildConfigField("String", "NOTION_CRASH_DB_ID", "\"$crashDbId\"")
        buildConfigField("String", "NOTION_TICKET_DB_ID", "\"$ticketDbId\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(project(":mba-android"))
    implementation(project(":mba-core"))
    implementation(project(":mba-notion"))
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
