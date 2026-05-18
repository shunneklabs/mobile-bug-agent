import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins.apply("maven-publish")

group = providers.gradleProperty("MBA_GROUP")
    .orElse("dev.sunnat629.mba")
    .get()

version = providers.gradleProperty("MBA_VERSION")
    .orElse("0.1.0-kotlinconf-SNAPSHOT")
    .get()

extensions.configure<PublishingExtension>("publishing") {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri(
                providers.gradleProperty("MBA_GITHUB_PACKAGES_URL")
                    .orElse("https://maven.pkg.github.com/shunneklabs/mobile-bug-agent")
                    .get()
            )
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .orElse(providers.gradleProperty("githubPackagesUser"))
                    .orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orElse(providers.environmentVariable("GH_TOKEN"))
                    .orElse(providers.gradleProperty("githubPackagesToken"))
                    .orNull
            }
        }
    }

    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Mobile Bug Agent ${project.name}")
            description.set("Mobile Bug Agent SDK module: ${project.name}")
            url.set("https://github.com/shunneklabs/mobile-bug-agent")
            licenses {
                license {
                    name.set("Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("sunnat629")
                    name.set("sunnat629")
                }
            }
            scm {
                connection.set("scm:git:https://github.com/shunneklabs/mobile-bug-agent.git")
                developerConnection.set("scm:git:ssh://git@github.com/shunneklabs/mobile-bug-agent.git")
                url.set("https://github.com/shunneklabs/mobile-bug-agent")
            }
        }
    }
}
