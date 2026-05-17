# 🏗️ MBA Architecture

This document describes the Mobile Bug Agent SDK, server, and demo architecture. MBA is split into a small public SDK surface plus internal modules for capture, analysis, ticketing, and guarded automation.

## Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    External Developer API                    │
│   MBA.init(...) → MBA.logError()                             │
│   MBA.setScreen() → MBA.addBreadcrumb()                     │
│   MBA.exceptionHandler (attach to CoroutineScope)            │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│                      mba-core (KMP)                          │
│  ┌──────────┐  ┌──────────────┐  ┌───────────────────────┐  │
│  │   MBA    │  │ BreadcrumbTr │  │   CrashWriter         │  │
│  │ (object) │  │    acker     │  │ (expect/actual)       │  │
│  └────┬─────┘  └──────────────┘  └───────────────────────┘  │
│       │                                                      │
│  ┌────▼─────────────────────────────────────────────────┐   │
│  │ Internal pipeline (all internal visibility)           │   │
│  │  ┌─────────────┐  ┌──────────────┐  ┌─────────────┐  │   │
│  │  │ PIISanitizer │  │CrashFingerpr │  │LocalDedupCa │  │   │
│  │  │ (regex PII   │  │   int        │  │   che       │  │   │
│  │  │  scrubbing)  │  │ (SHA-256)    │  │ (LRU+TTL)  │  │   │
│  │  └─────────────┘  └──────────────┘  └─────────────┘  │   │
│  └───────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────┐    │
│  │ Config: MBAConfig, MBAMode, LLM, LLMConfig           │    │
│  │ Models: RawCrashReport, ProcessedCrashReport,         │    │
│  │         Severity, DeviceContext, TicketResult          │    │
│  │ Interfaces: TicketBackend, CrashStore                 │    │
│  └──────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                     mba-agent (KMP)                           │
│  All types internal — zero public API surface                │
│                                                              │
│  ┌──────────────────────┐    ┌────────────────────────┐     │
│  │ CrashAnalysisAgent   │    │ KoogAgentFactory       │     │
│  │ (analysis pipeline)  │    │ (creates executors)    │     │
│  │                      │    │                        │     │
│  │ 1. PII scrub         │    │ ┌────────────────────┐ │     │
│  │ 2. Fingerprint       │    │ │SinglePromptExecutor│ │     │
│  │ 3. Dedup check       │    │ │(1 LLM call, fast)  │ │     │
│  │ 4. Koog analysis ────┼────│ ├────────────────────┤ │     │
│  │ 5. Result packaging  │    │ │MultiStepExecutor   │ │     │
│  └──────────────────────┘    │ │(3 LLM calls, debug)│ │     │
│                              │ └────────────────────┘ │     │
│  ┌─────────────────────┐     └────────────────────────┘     │
│  │ Model Clients       │                                    │
│  │ ├─ Gemini via Koog  │  API key in x-goog-api-key header │
│  │ ├─ OpenAI via Koog  │  API key in Authorization header  │
│  │ └─ Legacy fallback  │  Direct HTTP path retained        │
│  └─────────────────────┘                                    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                    mba-notion (KMP)                           │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ NotionTicketBackend                                   │   │
│  │                                                       │   │
│  │ Dual-DB strategy:                                     │   │
│  │ ┌─────────────────┐    ┌──────────────────────┐      │   │
│  │ │ 🐛 Bug Tickets  │◄──►│ 🔴 Crash Reports     │      │   │
│  │ │ (ALL issues)    │    │ (crash-only)         │      │   │
│  │ │                 │    │                      │      │   │
│  │ │ Name (title)    │    │ Title (title)        │      │   │
│  │ │ Severity        │    │ Severity             │      │   │
│  │ │ Description     │    │ Stack Trace          │      │   │
│  │ │ Fingerprint     │    │ Fingerprint          │      │   │
│  │ │ Device Matrix   │    │ Exception Type       │      │   │
│  │ │ Affected Screen │    │ Affected Devices     │      │   │
│  │ │ Possible Cause  │    │ Crash File / Line    │      │   │
│  │ │ Steps to Repro  │    │ AI Confidence        │      │   │
│  │ │ AI Confidence   │    │ App Version          │      │   │
│  │ │ App Version     │    │ OS Versions          │      │   │
│  │ │ Occurrences     │    │ Occurrence Count     │      │   │
│  │ │ Status          │    │ Status               │      │   │
│  │ │ Crash Report ──►│────│◄── Bug Ticket        │      │   │
│  │ └─────────────────┘    └──────────────────────┘      │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘

┌──────────────────────┐    ┌──────────────────────┐
│   mba-android        │    │   mba-jvm            │
│   (Android-only)     │    │   (JVM-only)         │
│                      │    │                      │
│ • MBAAndroid         │    │ • JVMCrashHandler    │
│ • MBACrashHandler    │    │   (Thread.DUEH)      │
│   (UncaughtExc.)    │    │                      │
│ • MBAInitializer     │    │ • PlatformInitializer│
│   (AndroidX Startup)│    │   (actual)           │
│ • CrashUploadWorker  │    │ • CrashWriter        │
│   (WorkManager)     │    │   (actual)           │
│ • AndroidContextColl │    │                      │
│ • PlatformInitializer│    │                      │
│   (actual)           │    │                      │
│ • CrashWriter        │    │                      │
│   (actual)           │    │                      │
└──────────────────────┘    └──────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                     mba-server (Ktor)                       │
│                                                             │
│  /report → CrashProcessingQueue → persisted JobStore        │
│     │                                                       │
│     ├─ /jobs/{id}: current status                           │
│     ├─ /events: SSE timeline for booth UI                   │
│     ├─ /version + /stats: health/readiness endpoints        │
│     └─ rate limit + API-key auth                            │
│                                                             │
│  Demo UI: static booth page renders queue + status events.  │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                     mba-github (KMP/JVM)                    │
│                                                             │
│  GitHubIssueBackend → creates labeled crash issues          │
│  GitHubSourceReader → reads files/snippets for analysis     │
│  GitHubPullRequestCreator → opens guarded PRs               │
│                                                             │
│  Guardrails: no main/master target, max 20 changed lines,   │
│  no new dependencies, no public API changes, target file    │
│  must exist.                                                │
└─────────────────────────────────────────────────────────────┘
```

## Crash Flow (end-to-end)

```
1. CRASH HAPPENS
   │
   ├─ Fatal: UncaughtExceptionHandler fires
   │         → MBA.handleCrash(isFatal=true)
   │
   └─ Non-fatal: MBA.logError(throwable)
                 → MBA.handleCrash(isFatal=false)
                 │
2. WRITE TO DISK (synchronous, ~2ms)
   │  CrashWriter.writeToDisk(crashDir, ...)
   │  → JSON file in app-internal storage
   │
3. PII SCRUB (regex, no network, ~1-3ms)
   │  PIISanitizer.scrub(stackTrace)
   │  → emails, phones, IPs, tokens → [REDACTED]
   │
4. FINGERPRINT (SHA-256, <1ms)
   │  CrashFingerprint.compute(exceptionType, stackTrace)
   │  → deterministic hash for dedup
   │
5. LOCAL DEDUP CHECK (<1ms)
   │  LocalDedupCache.contains(fingerprint)
   │  → if duplicate: skip LLM, update count
   │  → if new: continue to AI
   │
6. KOOG AI ANALYSIS (2-8 seconds)
   │  SinglePromptExecutor (1 model call):
   │  → parseStackTrace + classifySeverity + generateSummary
   │  → Returns: title, description, severity, confidence,
   │            crash file/line/method, possible cause, steps
   │
7. ROUTING + WORK CREATION
   │  NotionTicketBackend.createTicket(report)
   │  → Creates page in Bug Tickets DB (always)
   │  → Creates page in Crash Reports DB (if crash)
   │  → Links them via relation property
   │
   │  Optional GitHub path:
   │  → Create issue
   │  → Read source context
   │  → Run guardrails
   │  → Open branch/PR only if safe and enabled
   │
8. DONE
   → TicketResult { ticketId, url, success }
```

## Server demo flow

```text
mba-sample
  → POST /report
  → 202 Accepted { jobId }
  → CrashProcessingQueue marks QUEUED / ANALYZING / TICKET_CREATED / PR_OPENED / FAILED
  → JobStore persists state to disk
  → /events streams each status to the booth page
  → /jobs/{id} gives point-in-time status for clients and operators
```

The booth demo depends on this visible chain. Current Workstream D is adding full Koog tool-call events so the page can show not only final status, but each tool step.

## Public API Surface

External developers interact with **only these types**:

| Type | Module | Purpose |
|---|---|---|
| `MBA` | mba-core | Singleton entry point |
| `MBAConfig.Builder` | mba-core | DSL for configuration |
| `MBAMode` | mba-core | SdkOnly / Saas / SelfHosted |
| `LLM` + `LLMConfig` | mba-core | LLM provider factory |
| `TicketBackend` | mba-core | Interface for custom backends |
| `Severity` | mba-core | CRITICAL / HIGH / MEDIUM / LOW |
| `DeviceContext` | mba-core | Device info data class |
| `TicketResult` | mba-core | Ticket creation result |
| `NotionTicketBackend` | mba-notion | Notion implementation |

**Everything else is `internal`** — enforced by `explicitApi()` in every module.

## Logging

Uses **Kermit 2.1.0** (KMP-native).

- Gated by `MBAConfig.debug` — zero overhead when `false`
- Tags: `MBA/Core`, `MBA/Agent`, `MBA/Notion`, `MBA/PII`, `MBA/DedupCache`, `MBA/Fingerprint`
- Android: `android.util.Log` (Logcat)
- JVM: `println` with timestamp

## Security

- API keys sent via HTTP **headers**, never in URL query params
- `LLMConfig.toString()` masks the API key
- PII is scrubbed **before** any data leaves the device
- `MBAConfig` constructor is `internal` — forces use of validated Builder
- Server endpoint requires `X-MBA-API-Key` header auth
- Server has request rate limiting; booth CORS should be narrowed before public deployment
- GitHub auto-fix is guarded and kill-switch controlled

## Testing

```
mba-core/src/commonTest/
├── BreadcrumbTrackerTest.kt     (add, eviction, thread-safety)
├── fingerprint/CrashFingerprintTest.kt  (deterministic, line numbers, top frames)
├── pii/PIISanitizerTest.kt      (email, token, IP, custom patterns)
└── store/LocalDedupCacheTest.kt (put, contains, TTL, LRU, snapshot/restore)

mba-agent/src/commonTest/
└── CrashAnalysisAgentTest.kt    (full pipeline mock, duplicate detection, fallback)

mba-notion/src/commonTest/
└── NotionTicketBackendTest.kt   (ktor-client-mock, field mapping, HTTP errors)

mba-github/src/commonTest or jvmTest/
└── GitHub backend, source reader, guardrails, reviewer assignment tests

mba-server/src/test/
└── Ktor route, queue, job, SSE, and rate-limit tests
```

Run: `./gradlew allTests`

## Optional Modules

- **`mba-github`** — alternative `TicketBackend` (`GitHubIssueBackend`) plus guarded auto-fix primitives:
  - `GitHubAutoFixOpener.openAutoFix` — opens a tracking issue and a `autofix/issue-N-<slug>` branch off `GITHUB_BASE_BRANCH`.
  - `GitHubPullRequestCreator.openFix` — guard-railed PR creator (≤20 diff lines, no new deps, no public-API changes, refuses `main`/`master` base, labels `mba/ai-generated` + `do-not-merge-yet`).
  - Gated on the server by `RawCrashReport.autoFix`, severity (HIGH/CRITICAL only), and `GITHUB_*` env vars. See README §"Routing truth table" for full matrix.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
