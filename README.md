# 🐛 Mobile Bug Agent (MBA)

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.20-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/platform-Android%20%7C%20JVM-brightgreen)]()
[![Status](https://img.shields.io/badge/status-alpha-orange)]()

**Crashlytics catches your crashes. Mobile Bug Agent opens the ticket — and, for safe fixes, can start the pull request.**

MBA is an alpha Kotlin Multiplatform crash pipeline for Android and JVM apps. It captures fatal and non-fatal failures, scrubs private data, groups duplicates, asks an AI agent for root-cause analysis, then files structured work in Notion or GitHub.

> **KotlinConf Munich booth demo:** the live path is `tap crash → server job → Koog analysis → Notion/GitHub update → live booth timeline`. Auto-fix PR creation is guard-railed and kill-switched while Workstream D orchestration is being completed.

> **Status:** Alpha. Public API surface is intentionally small (`MBA`, `MBAConfig.Builder`, `MBAMode`, `LLM`, `TicketBackend`, `Severity`, `DeviceContext`, `TicketResult`, `NotionTicketBackend`) and may change before `1.0`. Pin a tag for stability.

## ✨ What MBA does

```text
Crash happens
  → report is written to disk before process death
  → PII is scrubbed
  → fingerprint + dedup keep repeats quiet
  → Koog-backed AI analysis explains likely cause
  → Notion ticket, GitHub issue, or guarded PR path is created
  → `/events` streams booth-visible progress
```

## 🎬 Demo media

The booth recording and 30-second animated GIF are tracked for the `v0.1.0-kotlinconf` polish pass. Until the asset is committed, run the sample app and the server booth page side by side:

- Android sample: `mba-sample`
- Live event wall: `mba-server` static booth page backed by `/events`
- Demo story: **one clean crash becomes one visible ticket/PR trail**

## 🏗️ Architecture at a glance

```text
Android/JVM app
    │
    ├─ mba-core: public API, config, crash model, PII scrub, fingerprint, dedup
    ├─ mba-android / mba-jvm: platform crash handlers + disk writer
    │
    ▼
Pending crash file / report upload
    │
    ├─ SdkOnly: local Koog analysis + TicketBackend
    └─ Saas/SelfHosted: mba-server `/report` queue
            │
            ├─ Koog agent analysis
            ├─ Notion backend: Bug Tickets + Crash Reports
            ├─ GitHub backend: issues, guarded branches, PR opener
            └─ `/events`: SSE timeline for booth dashboard
```

More detail: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## 📦 Modules

| Module | Role |
|---|---|
| `mba-core` | KMP shared API, models, config, crash capture, PII sanitizer, fingerprinting, dedup cache |
| `mba-agent` | Koog-backed crash analysis pipeline with selectable provider/model support and raw fallback |
| `mba-notion` | Notion API integration with linked Bug Tickets and Crash Reports databases |
| `mba-github` | GitHub issue backend, source reader, guardrails, auto-fix branch and PR creator |
| `mba-android` | Android crash handler, AndroidX Startup, WorkManager upload path |
| `mba-jvm` | JVM crash handler for server and desktop runtimes |
| `mba-server` | Ktor ingest server with queue, job state, rate limit, persistence, SSE, booth page |
| `mba-sample` | Android demo app for stage-safe crash generation and SDK smoke tests |

## 📚 Documentation

- [SDKOnly Android implementation guide](docs/SDKONLY_IMPLEMENTATION_GUIDE.md)
- [SDKOnly mode overview](docs/MBA_AGENT_SDKONLY.md)
- [Mobile agent and core architecture](docs/MOBILE_AGENT_AND_CORE_OVERVIEW.md)
- [Monitoring and privacy boundary](docs/MONITORING_SECURITY_CLAIMS.md)
- [Repository architecture](docs/ARCHITECTURE.md)
- [Koog agent roadmap](docs/KOOG_AGENT_ROADMAP.md)

## 🚀 Quick Start: sample app

1. Clone the repo.
2. Add local secrets to `local.properties` (never commit them):
   ```properties
   NOTION_TOKEN=ntn_your_integration_token
   NOTION_TICKET_DB_ID_OR_URL=your_bug_tickets_db_id
   NOTION_CRASH_DB_ID_OR_URL=your_crash_reports_db_id
   GEMINI_API_KEY=AIzaSy...
   ```
3. Build and run `mba-sample` on a device or emulator.
4. Trigger a crash, relaunch, and confirm a Notion ticket or server job appears.

## 📦 SDKOnly integration

For a complete implementation walkthrough, read
[docs/SDKONLY_IMPLEMENTATION_GUIDE.md](docs/SDKONLY_IMPLEMENTATION_GUIDE.md).

At minimum, an Android app adds the Android adapter:

```kotlin
dependencies {
    implementation("dev.sunnat629.mba:mba-android:<version>")
}
```

When working inside this repository, use:

```kotlin
dependencies {
    implementation(project(":mba-android"))
}
```

Add optional delivery modules only when the app needs them:

```kotlin
dependencies {
    implementation("dev.sunnat629.mba:mba-notion:<version>")
    implementation("dev.sunnat629.mba:mba-github:<version>")
}
```

Initialize as early as possible in `Application.onCreate`:

```kotlin
class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        MBAAndroid.install(this)

        val llmConfig = LLM.gemini(BuildConfig.GEMINI_API_KEY)

        MBA.configure(
            MBAConfig.Builder().apply {
                mode = MBAMode.SdkOnly(llm = llmConfig)
                useAgent = true
                debug = BuildConfig.DEBUG
            }.build(),
        )

        MBAAndroid.saveConfig(
            context = this,
            sendToBackend = false,
            llm = llmConfig,
            useAgent = true,
            callback = { event ->
                Log.i("MBA", "Crash group=${event.group.id}, title=${event.report.title}")
            },
            batchCallback = { batch ->
                Log.i("MBA", "Processed ${batch.totalCount} pending crash report(s)")
            },
            jsonCallback = { json ->
                Log.d("MBA", "Latest SDKOnly event JSON: $json")
            },
            batchJsonCallback = { json ->
                Log.d("MBA", "SDKOnly batch JSON: $json")
            },
            debug = BuildConfig.DEBUG,
        )
    }
}
```

Use the runtime API to add safe context and report non-fatal errors:

```kotlin
MBA.setScreen("CheckoutScreen")
MBA.addBreadcrumb("Tapped Pay")
MBA.logError(error, metadata = mapOf("flow" to "checkout"))

val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + MBA.exceptionHandler)
```

Optional Notion/GitHub delivery is configured by adding the integration module
and registering ticket backends with `MBAAndroid.setTicketBackends(...)`. Apps
that do not need Notion or GitHub can stay callback-only and handle the JSON in
their own app layer.

Treat crash context like logs. Do not put emails, tokens, payment data, raw user
input, or private content in exception messages, breadcrumbs, screen names, or
custom metadata.

SDKOnly does not bind you to Gemini. The app can choose any Koog-backed provider
supported by MBA:

```kotlin
LLM.gemini(apiKey, model = "gemini-2.0-flash")
LLM.openAI(apiKey, model = "gpt-4o-mini")
LLM.anthropic(apiKey, model = "claude-sonnet-4-20250514")
LLM.ollama(model = "llama3.2:latest", endpoint = "http://10.0.2.2:11434")
LLM.openRouter(apiKey, model = "anthropic/claude-3.5-sonnet")
LLM.mistral(apiKey, model = "mistral-large-latest")
LLM.deepSeek(apiKey, model = "deepseek-chat")
LLM.dashScope(apiKey, model = "qwen-plus")
LLM.custom(apiKey = "", endpoint = "http://10.0.2.2:1234/v1", model = "local-model")
```

## 🤖 Why Koog

MBA uses [JetBrains Koog](https://github.com/JetBrains/koog) as the Kotlin-native AI agent runtime. Koog keeps the agent layer close to the rest of the codebase: prompts, tool calls, model clients, retries, and structured outputs stay in Kotlin instead of a separate Python service.

Current shape:

- `mba-agent` runs Koog by default for crash analysis.
- Gemini, OpenAI, Anthropic, Ollama/local, OpenRouter, Mistral, DeepSeek,
  DashScope, and OpenAI-compatible custom endpoints sit behind the Koog-backed
  executor.
- A legacy direct HTTP path remains available while the demo orchestration matures.
- Workstream D is wiring Notion/GitHub/source/guardrail actions into visible Koog tool events.

## ✅ Current status

- [x] **Core SDK** — `MBA` singleton, 2-phase initialization, crash capture to disk
- [x] **Breadcrumb tracking** — thread-safe bounded history
- [x] **PII sanitizer** — email, phone, IP, token, and custom pattern scrubbing before network calls
- [x] **Crash fingerprinting + dedup** — stable grouping for repeated failures
- [x] **Android upload path** — AndroidX Startup plus WorkManager processing on next launch
- [x] **Android ANR exits** — Android 11/API 30+ previous-process ANR detection after app restart
- [x] **SDKOnly callbacks** — latest and batch object/JSON callbacks for app-owned workflows
- [x] **Koog analysis** — selectable provider/model analysis through `mba-agent`, with fallback path retained
- [x] **Notion integration** — linked Bug Tickets and Crash Reports databases
- [x] **GitHub integration** — issue creation, source reader, reviewer lookup, guarded PR opener
- [x] **Ktor server** — `/report`, `/jobs/{id}`, `/events`, `/version`, `/stats`, rate limit, persisted job state
- [x] **Booth page foundation** — live SSE timeline and PR-opened highlight in `mba-server`
- [x] **Apache 2.0 license** — full license text committed

## 🔲 Next up

- [ ] **Workstream D orchestration** — Koog tool wrappers, deterministic tests, and visible tool-call SSE events
- [ ] **Booth polish** — one-button sample app, QR, branding, confetti, fallback video, rehearsal
- [ ] **Repo polish** — GIF/video asset, issue templates, release tag, GitHub topics, repository description
- [ ] **Ops** — Docker/compose or reliable laptop deployment, demo reset script, real deployment rehearsal
- [ ] **Future SDK scope** — iOS support, ANR watchdog, mapping-file deobfuscation, Jira/Linear backends, Maven Central publish

## 🛠️ Tech Stack

| Tech | Version |
|---|---|
| Kotlin | 2.3.20 |
| AGP | 9.1.0 |
| Ktor | 3.4.2 |
| kotlinx.serialization | 1.10.0 |
| kotlinx.coroutines | 1.10.2 |
| Kermit (logging) | 2.1.0 |
| Compose BOM | 2026.03.01 |
| Targets | Android (API 26+), JVM |

## 🧭 Routing truth table

| Input | Default behavior | Notes |
|---|---|---|
| `SdkOnly` app crash | Analyze locally, send to configured `TicketBackend` | Good for direct Notion/GitHub use without server hosting |
| `SelfHosted` / server report | Queue job, persist state, stream updates over SSE | Used by booth dashboard and demos |
| High/critical crash | Notify/ticket path | Auto-fix remains kill-switch controlled |
| Low-risk fix candidate | GitHub issue + guarded branch/PR path | Guardrails reject `main`/`master`, large diffs, new deps, public API changes |

## 🤝 Contributing

Contributions welcome — issues, PRs, and discussions. See [CONTRIBUTING.md](CONTRIBUTING.md) and look for `good first issue` labels on the [issue tracker](https://github.com/shunneklabs/mobile-bug-agent/issues). For Munich demo work, the highest-value areas are Workstream D orchestration, booth polish, and repo polish.

## 📄 License

Apache License 2.0 — see [LICENSE](LICENSE).

```
Copyright 2025-2026 Mohi Us Sunnat and Mobile Bug Agent contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```
