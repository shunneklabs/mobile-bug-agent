# MBA — What’s Done & What’s Next (Phased Roadmap)

This document summarizes the current implementation status of **Mobile Bug Agent (MBA)** and outlines the next phases.

> Guiding principle: **Open-core SDK first, optional cloud later.**
> The free SDK is the distribution channel; paid tiers add operational leverage, automation, and insights.

---

## 0) Current direction (MVP)
**MVP goal:** Crash an Android app → AI-generated bug ticket appears in Notion within ~30 seconds, with **zero infrastructure**.

**MVP architecture:**
- Android app + MBA SDK
- Crash capture → disk persistence
- Next launch: on-device analysis pipeline
- Notion as:
  - Crash store (dedup + occurrence tracking)
  - Ticket backend (human-readable bug ticket)

(See: *MBA Code Blueprint* + *MBA MVP — SDK-Only, Notion-Powered, Zero Infrastructure*.)

---

## 1) What we’ve implemented so far

### 1.1 Repo & multi-module setup
- Multi-module Gradle project with version catalog (`gradle/libs.versions.toml`).
- Modules present in the repo: `mba-core`, `mba-android`, `mba-agent`, `mba-notion`, `mba-server`, `mba-sample`.

### 1.2 `mba-core` (KMP foundation)
Implemented building blocks for a multiplatform SDK core:
- Public SDK API (`MBA`) with two-phase initialization concept.
- Crash persistence surface (`CrashWriter` expect/actual) for:
  - `androidMain`
  - `jvmMain`
- Crash context primitives:
  - `BreadcrumbTracker` (thread-safe ring buffer)
- Core models such as `RawCrashReport` / `ProcessedCrashReport`.
- Backend abstraction points:
  - `CrashStore`
  - `TicketBackend`

### 1.3 `mba-android` (Android capture + scaffolding)
- `MBAAndroid.install(context)` installs `UncaughtExceptionHandler` and bootstraps `mba-core` crash dir.
- Android crash handler (`MBACrashHandler`) forwards to `MBA.handleCrash(...)`.
- Added MVP scaffolding files aligned with the blueprint:
  - `PendingCrashProcessor` (currently scaffold)
  - `CrashUploadWorker` (currently scaffold)
  - `MBAInitializer` (AndroidX Startup)

### 1.4 `mba-agent` (analysis pipeline)
- Added models, prompts, tools, and `CrashAnalysisAgent` on branch `feat/mba-agent-gemini`.
- Current state: **deterministic MVP scaffolding** (parsing + severity + summary) with prompts prepared for Koog/Gemini wiring.

### 1.5 `mba-notion` (Notion integration)
- Implemented:
  - `NotionConfig`
  - `NotionClient` (Ktor)
  - Notion request/response model scaffolding
  - `NotionTicketBackend` (creates a Notion page as a bug ticket)

### 1.6 `mba-server` (currently present, but NOT required for SDK-only MVP)
- Added a Ktor `POST /report` endpoint as an orchestration option.
- Note: the **SDK-only MVP does not require** a server; this module is for future paid tiers / centralized orchestration.

---

## 2) What’s missing (to complete the SDK-only MVP)

### 2.1 Notion as Crash Store (dedup + occurrence tracking)
Implement `mba-notion/NotionCrashStore` as `CrashStore`:
- `findByFingerprint(fingerprint)` → query Crash Reports DB
- `insertCrash(report)` → create Crash Report row
- `incrementCount(groupId, device)` → update count + device matrix + last seen
- `linkTicket(groupId, ticketId)` → relation Crash Report ↔ Bug Ticket

Also required in `NotionClient`:
- `updatePage(...)` (PATCH `/v1/pages/{page_id}`)

### 2.2 Android “next launch” processing
Implement real logic in `PendingCrashProcessor`:
1) Scan disk for pending crash JSON files
2) Deserialize `RawCrashReport`
3) Run analysis pipeline
4) Dedup:
   - duplicate → update Crash Report
   - new → create Crash Report + create Bug Ticket + link relation
5) Remove/mark processed crash files

### 2.3 Koog + Gemini Flash wiring
Replace heuristic tool implementations with Koog tool calls producing strict JSON:
- StackTraceParser
- SeverityClassifier
- SummaryGenerator
- (Optional) SmartFingerprint

### 2.4 Sample app (demo)
Implement `mba-sample`:
- `SampleApplication` wiring (`MBAAndroid.install`, `MBA.configure`)
- Compose Material 3 UI:
  - crash buttons (NPE / IllegalState / OOM / non-fatal)
  - local status (pending crashes / last ticket URL)
- Edge-to-edge UI + adaptive icon

---

## 3) Next phases (product + architecture roadmap)

This section aligns engineering phases to the open-core → SaaS model.

### Phase 1 — OSS SDK (MVP)
**Outcome:** “Crash → Notion ticket in 30 seconds” demo.
- Android-only is acceptable.
- No cloud required.

### Phase 2 — OSS maturity + iOS/JVM expansion
**Outcome:** true multiplatform confidence.
- iOS support (capture + disk persistence)
- Improve dedup + device matrix
- Hardening: retries, rate-limits, offline behavior
- Documentation + templates (Notion DB templates)

### Phase 3 — MBA Cloud (paid)
**Outcome:** team visibility and centralized intelligence.
- Hosted ingestion + dashboards
- Cross-device / cross-app dedup at scale
- Slack alerts + weekly digest
- Controlled LLM costs via dedup and sampling

### Phase 4 — “Crash → PR” automation (paid)
**Outcome:** MBA becomes a *fix* engine, not just a reporting tool.
- Root cause analysis (repo-aware)
- Patch suggestions
- Auto-PR generation with review workflows

### Phase 5 — Enterprise tier
**Outcome:** compliance + control.
- Self-hosted deployments
- SSO/SAML, audit logs, RBAC
- On-prem LLM option
- SLA + priority support

---

## 4) Operating principles (architecture)
- **Clean boundaries**: `mba-core` contains interfaces + models; platform modules implement capture; backend modules implement storage/tickets.
- **Privacy-first**: PII scrubbing happens before any external call.
- **Cost control**: dedup first; LLM only for unique crashes.
- **Open-core**: keep the SDK excellent; cloud adds leverage, not basic functionality.

---

## 5) Quick developer-facing target API (MVP)
```kotlin
MBAAndroid.install(context) // early, fast

MBA.configure(
  MBAConfig.Builder().apply {
    // Gemini Flash API key
    // Notion token + crash DB + ticket DB
  }.build()
)
```

---

## Appendix: Environment variables (mba-server, optional)
If you run `mba-server` (not required for SDK-only MVP):
- `NOTION_TOKEN`
- `NOTION_DATABASE_ID`
- `NOTION_VERSION` (optional)
- `MBA_SERVER_HOST` (optional)
- `MBA_SERVER_PORT` (optional)
