# GitHub Integration Guide

This guide explains how an app team connects Mobile Bug Agent SDKOnly mode to
their own GitHub repository.

MBA does not require access to an MBA-owned GitHub organization. The app owner
provides a GitHub token, repository owner, and repository name. The SDK can then
create or update GitHub issues from grouped crash reports.

## What The GitHub Module Does

`mba-github` implements `TicketBackend`.

In SDKOnly mode it can:

- create one GitHub issue for a new crash group
- update the existing issue when the same crash happens again
- include Koog fields such as severity, confidence, steps to reproduce, and
  possible cause
- include device/app metadata, breadcrumbs, and stack trace
- label issues by severity

## 1. Add The Dependency

Published SDK:

```kotlin
dependencies {
    implementation("dev.sunnat629.mba:mba-android:<version>")
    implementation("dev.sunnat629.mba:mba-github:<version>")
}
```

Repository build:

```kotlin
dependencies {
    implementation(project(":mba-android"))
    implementation(project(":mba-github"))
}
```

## 2. Create A GitHub Token

Create a token that can write issues in the target repository.

For a fine-grained personal access token, use the target repository and grant:

| Permission | Access |
|---|---|
| Metadata | Read |
| Issues | Read and write |

For classic personal access tokens, the practical minimum for private repos is
usually `repo`. For public repos, `public_repo` may be enough.

Store the token outside source control.

Sample `local.properties`:

```properties
GITHUB_TOKEN=github_pat_or_app_token
GITHUB_OWNER=your_org_or_username
GITHUB_REPO=your_repo
```

Do not commit the token.

## 3. Wire The SDK

Create the backend in your app layer:

```kotlin
val githubBackend = GitHubIssueBackend(
    token = BuildConfig.GITHUB_TOKEN,
    owner = BuildConfig.GITHUB_OWNER,
    repo = BuildConfig.GITHUB_REPO,
)
```

Register it after `MBA.configure(...)` and before pending crashes are processed:

```kotlin
MBAAndroid.setTicketBackends(
    githubBackend = githubBackend,
)
```

A complete SDKOnly setup:

```kotlin
class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val llmConfig = LLM.gemini(BuildConfig.GEMINI_API_KEY)

        MBA.configure(
            MBAConfig.Builder().apply {
                mode = MBAMode.SdkOnly(llm = llmConfig)
                debug = BuildConfig.DEBUG
            }.build(),
        )

        MBAAndroid.setTicketBackends(
            githubBackend = GitHubIssueBackend(
                token = BuildConfig.GITHUB_TOKEN,
                owner = BuildConfig.GITHUB_OWNER,
                repo = BuildConfig.GITHUB_REPO,
            ),
        )

        MBAAndroid.saveConfig(
            context = this,
            llm = llmConfig,
            debug = BuildConfig.DEBUG,
        )

        MBAAndroid.install(this)
    }
}
```

To use both Notion and GitHub:

```kotlin
MBAAndroid.setTicketBackends(
    notionBackend = notionBackend,
    githubBackend = githubBackend,
)
```

## 4. Issue Shape

MBA creates issues with:

- title from Koog analysis or raw fallback
- severity and confidence
- crash fingerprint
- occurrence timestamp
- description
- possible cause
- steps to reproduce
- location when available
- device context
- app version and build type
- breadcrumbs
- stack trace
- labels such as `mba/high` and `mba/auto-generated`

If Koog fails, MBA still creates a structured raw-fallback issue. In that case
confidence is `0%`, and the callback JSON contains `analysisSource =
RAW_FALLBACK` plus `analysisError`.

## 5. Duplicate Behavior

MBA groups crashes by fingerprint.

For a new group:

- GitHub creates one issue.

For a duplicate:

- GitHub updates the existing issue when the stored issue number exists.
- The updated issue body includes occurrence count, unique devices, last seen,
  and the latest enriched report when Koog succeeds.

It should not create a second issue for the same `appId + environment +
fingerprint`.

## 6. Callback-Only Alternative

If an app does not want MBA to call GitHub directly, do not add or register
`mba-github`.

Use callbacks instead:

```kotlin
MBAAndroid.saveConfig(
    context = this,
    llm = llmConfig,
    jsonCallback = { json ->
        // App owns upload, retry, GitHub App routing, or custom workflow.
    },
)
```

In callback-only mode, MBA processes the crash and emits JSON. The app owns all
delivery, retry, and GitHub authorization behavior.

## Troubleshooting

`401 Unauthorized`:

- Token is missing, expired, revoked, or not loaded into the app.

`403 Forbidden`:

- Token does not have issue write permission, organization policy blocks the
  token, or the repository is outside the token scope.

`404 Not Found`:

- Owner/repo is wrong, the repository is private and outside token scope, or the
  token cannot access it.

Duplicate issues are created:

- Confirm the app is using the same `appId`, build type/environment, and crash
  fingerprint.
- Confirm the local aggregation store is not being cleared between runs.
- Confirm the first issue id was saved before the second occurrence was
  processed.

Issue exists but has no Koog fields:

- Check callback JSON for `analysisSource`.
- `KOOG` means agent analysis ran.
- `RAW_FALLBACK` means the LLM/Koog path failed and MBA used structured fallback.
