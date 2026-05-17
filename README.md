# 🐛 Mobile Bug Agent (MBA)

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.20-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/platform-Android%20%7C%20JVM-brightgreen)]()
[![Status](https://img.shields.io/badge/status-alpha-orange)]()

An AI-powered crash reporting SDK for **Kotlin Multiplatform (KMP)** and **Android**.

Captures crashes → analyzes with AI → creates structured bug tickets in Notion (or opens GitHub issues + PRs).

> **Status:** Alpha. Public API surface is small (`MBA`, `MBAConfig.Builder`, `MBAMode`, `LLM`, `TicketBackend`, `Severity`, `DeviceContext`, `TicketResult`, `NotionTicketBackend`) but may evolve before `1.0`. Pin a tag for stability.

## ✨ What it does

```
Crash happens → Written to disk instantly → PII scrubbed → Fingerprinted →
Deduplicated → AI analyzes (Gemini/OpenAI) → Bug ticket created in Notion
```

## 🏗️ Project Structure

| Module | Role |
|---|---|
| `mba-core` | KMP shared library — models, config, crash capture, PII sanitizer, fingerprinting, dedup cache |
| `mba-agent` | KMP shared — AI crash analysis pipeline (LLM callers, prompts, single-prompt executor) |
| `mba-notion` | KMP shared — Notion API integration (dual-DB: Bug Tickets + Crash Reports) |
| `mba-github` | KMP shared — GitHub backend: ticket backend + auto-fix issue/branch opener + guard-railed PR creator |
| `mba-android` | Android-specific — UncaughtExceptionHandler, WorkManager, AndroidX Startup |
| `mba-jvm` | JVM-specific — crash handler for server/desktop |
| `mba-server` | Ktor server — receives crash reports, runs AI, creates tickets |
| `mba-sample` | Android sample app — 5 crash trigger buttons + Notion integration test |

## 🚀 Quick Start (Sample App)

1. Clone the repo
2. Add to `local.properties` (not committed to git):
   ```properties
   NOTION_TOKEN=ntn_your_integration_token
   NOTION_TICKET_DB_ID_OR_URL=your_bug_tickets_db_id
   NOTION_CRASH_DB_ID_OR_URL=your_crash_reports_db_id
   GEMINI_API_KEY=AIzaSy...
   ```
3. Build & run `mba-sample` on a device/emulator
4. Tap any crash button → real exception is caught → sent to your Notion DBs

## 📦 SDK Integration (for your app)

```kotlin
// Application.onCreate()
MBA.init(crashDir = filesDir.resolve("mba-crashes").absolutePath) {
    mode = MBAMode.SdkOnly(
        llmApiKey = "your-gemini-key",
        ticketBackend = NotionTicketBackend(
            apiKey = "secret_...",
            bugTicketDbId = "...",
            crashReportDbId = "...",
        ),
    )
    debug = true // enables internal SDK logging (Kermit → Logcat)
}

// Track screens
MBA.setScreen("CheckoutScreen")

// Add breadcrumbs
MBA.addBreadcrumb("User tapped checkout")

// Log non-fatal errors
try { riskyOperation() } catch (e: Exception) {
    MBA.logError(e, mapOf("context" to "payment"))
}

// Capture coroutine crashes
val scope = CoroutineScope(Dispatchers.IO + MBA.exceptionHandler)
```

## ✅ Done

- [x] **Core SDK** — `MBA` singleton with 2-phase init, crash capture to disk
- [x] **Breadcrumb tracking** — thread-safe, bounded (50 entries)
- [x] **PII sanitizer** — regex-based scrubbing (email, phone, IP, tokens) before data leaves device
- [x] **Crash fingerprinting** — SHA-256 hash of exception type + top N frames
- [x] **Local dedup cache** — LRU with TTL, prevents re-processing known crashes
- [x] **AI crash analysis** — Gemini + OpenAI LLM callers with single-prompt optimization
- [x] **Notion integration** — dual-DB: Bug Tickets (all) + Crash Reports (crash-only), linked via relation
- [x] **Ktor server** — `/report` endpoint with API key auth, fail-fast config, Dispatchers.IO
- [x] **Server dedup persistence** — cache saved to disk JSON, survives restarts
- [x] **Sample app** — 5 crash trigger buttons, all send to Notion directly
- [x] **Kermit logging** — KMP-native logger, gated by `debug` flag, zero overhead in production
- [x] **Minimal public API** — `explicitApi()` enforced, only 8 public types
- [x] **Security** — API keys in headers (not URLs), `LLMConfig.toString()` masks keys
- [x] **Unit tests** — PIISanitizer, CrashFingerprint, LocalDedupCache, BreadcrumbTracker, CrashAnalysisAgent (mock), NotionTicketBackend (mock)
- [x] **Version catalog** — all deps in `libs.versions.toml`, parallel builds enabled
- [x] **Convention plugins** — `build-logic/` with shared KMP + JVM config

## 🔲 Coming Up

- [ ] **WorkManager pipeline** — auto-process crash files on next app launch (PendingCrashProcessor)
- [ ] **Anthropic + Ollama LLM callers** — currently only Gemini + OpenAI
- [ ] **GitHub Issues backend** — `GitHubTicketBackend` as alternative to Notion
- [ ] **Jira backend** — `JiraTicketBackend`
- [ ] **Linear backend** — `LinearTicketBackend`
- [ ] **iOS support** — add `iosMain` source sets, NSException handler
- [ ] **Compose Multiplatform sample** — shared UI across Android + iOS
- [ ] **ProGuard/R8 mapping** — deobfuscate stack traces from release builds
- [ ] **Rate limiting** — server-side rate limiter per API key
- [ ] **Dashboard view** — Notion dashboard template for crash analytics
- [ ] **ANR detection** — watchdog timer for main thread hangs
- [ ] **Network condition capture** — WiFi/cellular/offline context in crash reports
- [ ] **User identification** — optional user ID/email for crash correlation
- [ ] **Crash-free sessions metric** — track session health
- [ ] **Publish to Maven Central** — `dev.sunnat629.mba:mba-core`, `mba-android`, `mba-notion`

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

## 🤝 Contributing

Contributions welcome — issues, PRs, and discussions. See [CONTRIBUTING.md](CONTRIBUTING.md) and look for `good first issue` labels on the [issue tracker](https://github.com/sunnat629/mobile-bug-agent/issues).

## 📄 License

Apache License 2.0 — see [LICENSE](LICENSE).

```
Copyright 2025 Mohi Us Sunnat and Mobile Bug Agent contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```
