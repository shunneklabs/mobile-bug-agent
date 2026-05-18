# GitHub Packages SDK Integration

This guide explains how to publish and consume Mobile Bug Agent SDK artifacts
through GitHub Packages before Maven Central is available.

GitHub Packages is useful for controlled pre-release validation. It is not as
frictionless as Maven Central because Gradle consumers need GitHub credentials
to resolve packages.

## Published Modules

The publication workflow publishes SDK modules only:

| Module | Coordinate |
|---|---|
| `mba-core` | `dev.sunnat629.mba:mba-core:<version>` |
| `mba-agent` | `dev.sunnat629.mba:mba-agent:<version>` |
| `mba-android` | `dev.sunnat629.mba:mba-android:<version>` |
| `mba-notion` | `dev.sunnat629.mba:mba-notion:<version>` |
| `mba-github` | `dev.sunnat629.mba:mba-github:<version>` |
| `mba-jvm` | `dev.sunnat629.mba:mba-jvm:<version>` |

`mba-sample` and `mba-server` are not SDK artifacts and are not published.

## Versioning

For KotlinConf/pre-release validation use versions such as:

```text
0.1.0-kotlinconf-SNAPSHOT
0.1.0-kotlinconf.1
0.1.0-kotlinconf.2
```

Release-like versions should be immutable. Use `SNAPSHOT` only for active
iteration.

## Publishing From GitHub Actions

The workflow is `.github/workflows/publish-github-packages.yml`.

It supports:

- manual dispatch with a version input
- tag publishing for tags named `v*`

Examples:

```text
workflow_dispatch version = 0.1.0-kotlinconf.1
git tag v0.1.0-kotlinconf.1
git push origin v0.1.0-kotlinconf.1
```

The workflow uses GitHub's built-in `GITHUB_TOKEN` with `packages: write`.

## Publishing From A Local Machine

Use a GitHub token that can write packages to the repository.

`~/.gradle/gradle.properties`:

```properties
gpr.user=your_github_username
gpr.key=github_pat_with_package_write
```

Then run:

```bash
./gradlew publish -PMBA_VERSION=0.1.0-kotlinconf-SNAPSHOT
```

For local validation without pushing to GitHub Packages:

```bash
./gradlew publishToMavenLocal -PMBA_VERSION=0.1.0-local
```

Then compile the sample against the locally published artifacts:

```bash
./gradlew :mba-sample:compileDebugKotlin \
  -PMBA_USE_MAVEN_LOCAL=true \
  -PMBA_SAMPLE_USE_PUBLISHED_SDK=true \
  -PMBA_SAMPLE_SDK_VERSION=0.1.0-local
```

## Consuming From An External Android App

Add the GitHub Packages repository in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            name = "MobileBugAgentGitHubPackages"
            url = uri("https://maven.pkg.github.com/shunneklabs/mobile-bug-agent")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .get()
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orElse(providers.environmentVariable("GH_TOKEN"))
                    .get()
            }
        }
    }
}
```

Add dependencies in the app module:

```kotlin
dependencies {
    implementation("dev.sunnat629.mba:mba-android:0.1.0-kotlinconf.1")
}
```

Add optional integrations only if the app wants MBA to call those providers
directly:

```kotlin
dependencies {
    implementation("dev.sunnat629.mba:mba-notion:0.1.0-kotlinconf.1")
    implementation("dev.sunnat629.mba:mba-github:0.1.0-kotlinconf.1")
}
```

Callback-only SDKOnly mode does not require `mba-notion` or `mba-github`.

## Credential Options For Consumers

Use `~/.gradle/gradle.properties`:

```properties
gpr.user=your_github_username
gpr.key=github_pat_with_package_read
```

Or environment variables:

```bash
export GITHUB_ACTOR=your_github_username
export GITHUB_TOKEN=github_pat_with_package_read
```

For GitHub Packages, consumers typically need a token with package read access.
For private repositories/packages, the token must also be allowed to access the
repository/package.

## Testing The Sample App Against Packages

By default, `mba-sample` uses local project dependencies. To test the sample
against published GitHub Packages artifacts:

```properties
MBA_SAMPLE_USE_PUBLISHED_SDK=true
MBA_SAMPLE_SDK_VERSION=0.1.0-kotlinconf.1
GITHUB_PACKAGES_USER=your_github_username
GITHUB_PACKAGES_TOKEN=github_pat_with_package_read
```

Then run:

```bash
./gradlew :mba-sample:compileDebugKotlin
```

For local Maven validation, also pass `-PMBA_USE_MAVEN_LOCAL=true`.

Keep `MBA_SAMPLE_USE_PUBLISHED_SDK=false` or unset for normal repository
development.

## Troubleshooting

`401 Unauthorized`:

- Missing token, expired token, wrong username, or Gradle did not load
  credentials.

`403 Forbidden`:

- Token does not have package read/write access, or organization policy blocks
  the token.

`404 Not Found`:

- Package version was not published, repository URL is wrong, or token cannot
  access the package.

Dependency still resolves from project:

- Confirm `MBA_SAMPLE_USE_PUBLISHED_SDK=true`.
- Run with `--refresh-dependencies` after publishing a new version.

Publishing publishes too much:

- Only SDK modules apply `gradle/publishing.gradle.kts`.
- `mba-sample` and `mba-server` should not apply the publishing script.

## Future Maven Central Path

GitHub Packages is a pre-release path. Maven Central should become the default
public SDK distribution because it does not require every consumer to configure
GitHub package credentials.
