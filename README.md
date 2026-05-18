# Mobile Bug Agent

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.20-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/platform-Android%20%7C%20JVM-brightgreen)]()
[![Status](https://img.shields.io/badge/status-alpha-orange)]()

Mobile Bug Agent is a Kotlin crash pipeline for Android apps.

Right now the main path is **SDKOnly**: the app captures crashes locally, groups
duplicates, runs optional agent analysis, and gives the app a structured result.
The app can keep that result in callbacks, create a Notion ticket, or create a
GitHub issue.

The repo also includes `mba-ios` and `mba-web` as future KMP platform
scaffolds. They reserve module boundaries for iOS and Web/Wasm work, but they
do not capture crashes yet.

The long-term plan is a hosted/SaaS path that centralizes crash streams across
devices, sends team notifications, and can prepare guarded draft PRs for safe
fixes. That part is future-facing, not the core claim of the SDK today.

## What It Does Today

```text
Android crash / non-fatal error / supported ANR exit
  -> capture raw report before or after process restart
  -> scrub obvious PII patterns
  -> compute fingerprint
  -> group repeated crashes locally
  -> run Koog-backed analysis when an LLM is configured
  -> fall back to raw structured report when analysis is unavailable
  -> return object and JSON callbacks
  -> optionally create/update Notion tickets or GitHub issues
```

Supported Android ANR handling means Android 11/API 30+ previous-process ANR
exit detection after the app restarts. It is not a live ANR watchdog yet.

## SDKOnly Install

Pre-release artifacts are currently available through GitHub Packages. Maven
Central is a future publishing target.

```kotlin
dependencies {
    implementation("dev.sunnat629.mba:mba-android:0.1.0-kotlinconf.1")
}
```

Add delivery modules only if the app wants MBA to call those services directly:

```kotlin
dependencies {
    implementation("dev.sunnat629.mba:mba-notion:0.1.0-kotlinconf.1")
    implementation("dev.sunnat629.mba:mba-github:0.1.0-kotlinconf.1")
}
```

GitHub Packages repository and credentials setup:
[docs/GITHUB_PACKAGES_SDK.md](docs/GITHUB_PACKAGES_SDK.md)

## Basic Android Setup

```kotlin
class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        MBAAndroid.install(this)

        val llm = LLM.gemini(BuildConfig.GEMINI_API_KEY)

        MBA.configure(
            MBAConfig.Builder().apply {
                mode = MBAMode.SdkOnly(llm = llm)
                debug = BuildConfig.DEBUG
            }.build(),
        )

        MBAAndroid.saveConfig(
            context = this,
            llm = llm,
            callback = { event ->
                Log.i("MBA", "Crash group=${event.group.id}, title=${event.report.title}")
            },
            jsonCallback = { json ->
                Log.d("MBA", json)
            },
            debug = BuildConfig.DEBUG,
        )

        MBAAndroid.flushPendingCrashes(this)
    }
}
```

Add app context where useful:

```kotlin
MBA.setScreen("CheckoutScreen")
MBA.addBreadcrumb("Tapped Pay")
MBA.logError(error, metadata = mapOf("flow" to "checkout"))
```

Full walkthrough:
[docs/SDKONLY_IMPLEMENTATION_GUIDE.md](docs/SDKONLY_IMPLEMENTATION_GUIDE.md)

## Optional Ticket Delivery

SDKOnly can stay callback-only. In that mode the app owns the result and decides
where it goes.

If you add `mba-notion` or `mba-github`, register ticket backends from your app:

```kotlin
MBAAndroid.setTicketBackends(
    notionBackend = notionBackend,
    githubBackend = githubBackend,
)
```

Details:

- [docs/NOTION_INTEGRATION.md](docs/NOTION_INTEGRATION.md)
- [docs/GITHUB_INTEGRATION.md](docs/GITHUB_INTEGRATION.md)

## Modules

| Module | Purpose |
|---|---|
| `mba-core` | Public API, models, PII scrubber, fingerprinting, local dedup helpers |
| `mba-agent` | Koog-backed crash analysis and raw fallback pipeline |
| `mba-android` | Android crash capture, ANR exit capture, WorkManager processing |
| `mba-jvm` | JVM crash helper |
| `mba-ios` | Future iOS SDK scaffold |
| `mba-web` | Future Web/Wasm SDK scaffold |
| `mba-notion` | Optional Notion ticket backend |
| `mba-github` | Optional GitHub issue backend and experimental autofix helpers |
| `mba-server` | Experimental self-hosted ingest/SSE demo server |
| `mba-sample` | Android sample app |

## Current Boundaries

- SDKOnly grouping is local to the app install.
- Direct GitHub issue updates work best when the same install has already stored
  the GitHub issue id.
- Cross-device duplicate GitHub issue merging needs centralized lookup or SaaS
  aggregation.
- `mba-ios` and `mba-web` are placeholders, not production capture adapters.
- Slack notifications are not implemented in the SDK today.
- Auto-fix PR creation is experimental. The repo has GitHub issue and branch
  helpers, but the full patch/build/draft PR loop is not the current SDK claim.

## Future Direction

The SaaS/self-hosted direction is:

```text
many app installs
  -> centralized crash ingest
  -> cross-device dedupe
  -> iOS and Web/Wasm adapters feeding the same models
  -> Notion / GitHub / Slack notifications
  -> optional guarded auto-fix
  -> draft PR for human review
```

Full KMP structure planning is tracked in
[#79](https://github.com/shunneklabs/mobile-bug-agent/issues/79).

Architecture notes and future planning live in `docs/`:

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- [docs/MOBILE_AGENT_AND_CORE_OVERVIEW.md](docs/MOBILE_AGENT_AND_CORE_OVERVIEW.md)
- [docs/KOOG_AGENT_ROADMAP.md](docs/KOOG_AGENT_ROADMAP.md)
- [docs/MONITORING_SECURITY_CLAIMS.md](docs/MONITORING_SECURITY_CLAIMS.md)

## Sample App

Build and run the sample:

```bash
./gradlew :mba-sample:assembleDebug
```

The sample is configured as a normal external app would be: it consumes the
published SDK coordinates instead of `implementation(project(":mba-*"))`.

## License

Apache License 2.0. See [LICENSE](LICENSE).

```
Copyright 2025-2026 Mohi Us Sunnat and Mobile Bug Agent contributors
```
