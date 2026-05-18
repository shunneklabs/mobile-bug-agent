val localProperties = java.util.Properties().apply {
    val file = file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val useMavenLocal = providers.gradleProperty("MBA_USE_MAVEN_LOCAL")
    .orElse(localProperties.getProperty("MBA_USE_MAVEN_LOCAL") ?: "false")
    .get()
    .toBooleanStrictOrNull()
    ?: false

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (useMavenLocal) {
            mavenLocal()
        }
        google()
        mavenCentral()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/shunneklabs/mobile-bug-agent")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .orElse(
                        localProperties.getProperty("GITHUB_PACKAGES_USER")
                            ?: localProperties.getProperty("GITHUB_USER")
                            ?: localProperties.getProperty("GITHUB_USERNAME")
                            ?: ""
                    )
                    .get()
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orElse(providers.environmentVariable("GH_TOKEN"))
                    .orElse(
                        localProperties.getProperty("GITHUB_PACKAGES_TOKEN")
                            ?: localProperties.getProperty("GITHUB_TOKEN")
                            ?: localProperties.getProperty("GH_TOKEN")
                            ?: ""
                    )
                    .get()
            }
        }
    }
}

rootProject.name = "mobile-bug-agent"

include(
    ":mba-core",
    ":mba-android",
    ":mba-jvm",
    ":mba-agent",
    ":mba-notion",
    ":mba-github",
    ":mba-server",
    ":mba-sample"
)
