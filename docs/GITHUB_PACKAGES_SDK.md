# Install MBA SDK From GitHub Packages

This guide is for app developers who want to install Mobile Bug Agent SDK in a
separate Android/KMP project before Maven Central is available.

GitHub Packages works well for private beta and event builds, but it requires
Gradle package credentials. When MBA is published to Maven Central later, the
repository and token setup below should no longer be needed.

## Available Artifacts

Use only the modules your app needs.

| Artifact | Use when |
|---|---|
| `dev.sunnat629.mba:mba-android:<version>` | Android app crash/ANR capture, persisted crash queue, SDKOnly agent entry point |
| `dev.sunnat629.mba:mba-agent:<version>` | Shared Koog-based crash analysis engine |
| `dev.sunnat629.mba:mba-core:<version>` | Shared models, fingerprinting, report payloads |
| `dev.sunnat629.mba:mba-ios:<version>` | Future iOS SDK scaffold; not a production crash adapter yet |
| `dev.sunnat629.mba:mba-web:<version>` | Future Web/Wasm SDK scaffold; not a production crash adapter yet |
| `dev.sunnat629.mba:mba-notion:<version>` | Optional Notion exporter for apps that want MBA to create/update Notion pages |
| `dev.sunnat629.mba:mba-github:<version>` | Optional GitHub exporter for apps that want MBA to create/update GitHub issues |
| `dev.sunnat629.mba:mba-jvm:<version>` | JVM/server-side integration helpers |

For a normal Android SDKOnly app, start with only:

```kotlin
dependencies {
    implementation("dev.sunnat629.mba:mba-android:0.1.0-kotlinconf.1")
}
```

`mba-android` pulls the required core/agent pieces it needs. Do not add
`mba-notion` or `mba-github` unless your app wants MBA to call those services
directly.

## Add The Package Repository

In the consuming app's `settings.gradle.kts`, add GitHub Packages:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

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

## Add Package Credentials

GitHub Packages normally requires a GitHub token even for read-only package
access.

Recommended local setup in `~/.gradle/gradle.properties`:

```properties
gpr.user=your_github_username
gpr.key=github_pat_with_package_read
```

CI setup can use environment variables instead:

```bash
export GITHUB_ACTOR=your_github_username
export GITHUB_TOKEN=github_pat_with_package_read
```

For private packages, make sure the token can access the repository or package.

## SDKOnly Setup

SDKOnly means the crash agent runs inside the app. The app can receive a
structured callback payload and decide what to do with it: show it, store it,
send it to Notion, create a GitHub issue, upload it to its own backend, or do
nothing.

Minimal app dependency:

```kotlin
dependencies {
    implementation("dev.sunnat629.mba:mba-android:0.1.0-kotlinconf.1")
}
```

Typical `Application.onCreate` shape:

```kotlin
class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        MBAAndroid.install(this)

        val llmConfig = LLM.gemini(BuildConfig.GEMINI_API_KEY)

        MBA.configure(
            MBAConfig.Builder().apply {
                mode = MBAMode.SdkOnly(llm = llmConfig)
                debug = BuildConfig.DEBUG
            }.build(),
        )

        MBAAndroid.saveConfig(
            context = this,
            sendToBackend = false,
            llm = llmConfig,
            useAgent = true,
            batchCallback = { batch ->
                // App owns the result. Persist, upload, display, or ignore it.
                val latest = batch.latest
            },
            batchJsonCallback = { json ->
                // App-owned JSON payload for custom integrations.
            },
            debug = BuildConfig.DEBUG,
        )

        MBAAndroid.flushPendingCrashes(this)
    }
}
```

The important integration contract is:

- `sendToBackend = false` keeps the SDK standalone.
- `useAgent = true` enables MBA analysis when the app provides valid LLM
  configuration.
- The callback gives the app the generated JSON/object so external developers
  are not forced to use MBA's Notion or GitHub integrations.
- `flushPendingCrashes(...)` should run after config and optional sinks are
  registered, so previous-run crashes are processed with the current app-layer
  routing.

If MBA analysis fails or is disabled, the callback should still receive the raw
crash details with device/app metadata so the host app can act on the event.

## Optional Notion Export

Only add this dependency if the app wants the SDK to call Notion directly:

```kotlin
dependencies {
    implementation("dev.sunnat629.mba:mba-notion:0.1.0-kotlinconf.1")
}
```

Without this module, the SDKOnly callback still gives the app the JSON payload,
and the app can implement its own Notion upload.

See [Notion integration](NOTION_INTEGRATION.md) for the required database
properties, token setup, and payload mapping.

## Optional GitHub Export

Only add this dependency if the app wants the SDK to call GitHub Issues
directly:

```kotlin
dependencies {
    implementation("dev.sunnat629.mba:mba-github:0.1.0-kotlinconf.1")
}
```

Without this module, the SDKOnly callback still gives the app the JSON payload,
and the app can implement its own GitHub issue creation.

See [GitHub integration](GITHUB_INTEGRATION.md) for token setup and issue
mapping.

## Versioning

KotlinConf/pre-release builds use versions such as:

```text
0.1.0-kotlinconf-SNAPSHOT
0.1.0-kotlinconf.1
0.1.0-kotlinconf.2
```

Prefer immutable release-like versions such as `0.1.0-kotlinconf.1` for demos
and external testing. Use `SNAPSHOT` only while actively iterating.

## Troubleshooting

`401 Unauthorized`:

- Token is missing, expired, or Gradle did not load it.
- Username does not match the token owner.

`403 Forbidden`:

- Token does not have package read access.
- Organization policy blocks the token.

`404 Not Found`:

- The version was not published.
- Repository URL is wrong.
- Token cannot access the package.

Gradle still cannot resolve packages:

- Run with `--refresh-dependencies`.
- Confirm the version exists in GitHub Packages.
- Confirm the repository block is in `settings.gradle.kts`, not only the app
  module build file.

## Maintainer Notes

This section is for Mobile Bug Agent maintainers working inside this repository.
External app developers do not need these steps.

Publish all SDK artifacts to GitHub Packages:

```bash
./gradlew publish -PMBA_VERSION=0.1.0-kotlinconf.1
```

Local validation without pushing packages:

```bash
./gradlew publishToMavenLocal -PMBA_VERSION=0.1.0-local
```

Compile the repo sample against local project modules:

```bash
./gradlew :mba-sample:compileDebugKotlin
```

`mba-sample` currently uses `implementation(project(":mba-*"))` for repo
development. To validate published artifacts, use a separate consumer app with
the coordinate examples above.

`mba-sample` and `mba-server` are not SDK artifacts and should not be
published.

## Future Maven Central Path

GitHub Packages is the pre-release distribution path. Maven Central should
become the default public SDK distribution because it does not require every
consumer to configure GitHub package credentials.
