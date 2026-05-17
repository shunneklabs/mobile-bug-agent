# MobileBugAgent — Junie Guidelines

Persistent context so Junie doesn't have to re-discover the same facts every session.
Update this file whenever a fact below drifts.

---

## 1. Modules at a glance

| Module        | Purpose                                                                 |
|---------------|-------------------------------------------------------------------------|
| `mba-core`    | KMP common: models (`RawCrashReport`, `ProcessedCrashReport`, `DeviceContext`, `TicketResult`), `MBA` entrypoint, `BreadcrumbTracker`, `LLMProviders`. |
| `mba-agent`   | LLM-backed crash analysis. `AgentFactory` → `CrashAnalysisExecutor` (Koog default, `SinglePromptExecutor`/`LegacyMultiStepExecutor` legacy). Callers: `GeminiLLMCaller`, `OpenAILLMCaller`. |
| `mba-notion`  | Notion ticket backend (`NotionTicketBackend.createTicket` → 2× POST `/v1/pages` + 1× PATCH for relation). |
| `mba-github`  | Optional autofix / PR opening path. `GitHubIssueBackend` (TicketBackend), `GitHubAutoFixOpener` (v1 issue+branch), `GitHubPullRequestCreator` (guard-railed PR). |
| `mba-server`  | Ktor (Netty) backend. See §3.                                           |
| `mba-android` | Android SDK wiring (`MBAAndroid`).                                      |
| `mba-jvm`     | JVM crash handler (`JVMCrashHandler`).                                  |
| `mba-sample`  | Demo app.                                                               |
| `assets/booth/` | Static SPA (`index.html`, `booth.js`) served by the server.           |

## 2. Crash flow (server-side)

`POST /report` → `CrashProcessingQueue.enqueue` → `startProcessing` → agent → (GitHub auto-fix? Notion? both? none) → terminal SSE event. The orchestration lives in `Application.processJob` + `handlePostAnalysis`.

_Note: the public endpoint is `POST /report` (not `/crashes`); old chats sometimes call it `/crashes`._

Agent (`mba-agent`) sub-steps inside the **ANALYZING** stage (currently NOT emitted as SSE):

1. `PIISanitizer.scrub(stackTrace)` — 1–3 ms
2. `CrashFingerprint.compute` — <1 ms
3. `LocalDedupCache.contains` — duplicate? skip LLM
4. `AgentFactory` picks executor (`SinglePromptExecutor` prod / `LegacyMultiStepExecutor` debug)
5. Build prompt (~5 ms)
6. **`GeminiLLMCaller.call` — 2–8 s** (the long part)
7. Parse JSON → `ProcessedCrashReport`
8. `SeverityRouter.shouldAutoFix` — CRITICAL/HIGH → eligible for auto-fix, MEDIUM/LOW → notify only.

Then, gated by `RawCrashReport.autoFix` & `RawCrashReport.skipNotion`:
- **GitHub auto-fix** (if `autoFix=true` & severity HIGH/CRITICAL & `GITHUB_*` env set): `GitHubAutoFixOpener.openAutoFix` → POST `/issues` + POST `/git/refs` (creates `autofix/issue-N-<slug>` branch off `GITHUB_BASE_BRANCH`). Emits `stage="github_pr"` progress + `prOpened` terminal SSE.
- **Notion** (if `skipNotion=false` & Notion creds set): `NotionTicketBackend.createTicket` → emits `stage="notion_ticket"` progress + `complete` SSE.
- **Both** (default when `autoFix=true`): both run; GitHub `prOpened` is the terminal SSE, Notion failures degrade to a warning instead of failing the job.
- **Neither** (dry-run: `skipNotion=true` & no GitHub): synthetic `analysis://<fingerprint>` complete event.

## 3. mba-server cheatsheet

- Entrypoint: `mba-server/src/main/kotlin/dev/sunnat629/mba/server/Application.kt`
  ```kotlin
  fun main() {
      embeddedServer(Netty, port = EnvConfig.port) { ... }
  }
  ```
- Run: `./gradlew :mba-server:run`
- Booth: `http://localhost:<port>/booth` (or `/?debug=1` for operator panel).
- Useful endpoints: `POST /report`, `GET /events` (SSE), `GET /stats`, `GET /jobs/{id}`, `GET /booth/pending-decisions`, `POST /booth/force-decision`, `POST /booth/reset`.
- Key files:
  - Queue + SSE emission: `mba-server/.../queue/CrashProcessingQueue.kt`
  - SSE route: `mba-server/.../sse/SseRoute.kt`
  - Routing/DI: `Application.kt`, `ServerModule.kt`, `di/`
  - Severity routing: `mba-server/.../orchestration/SeverityRouter.kt`

## 4. Known issues / gotchas

### 4.1 Booth "SSE disconnected, retrying…"
Almost always means **`mba-server` is not running** (or crashed). Start it via `./gradlew :mba-server:run` and reload the booth.

### 4.2 SSE Content-Type bug (`SseRoute.kt`) — ✅ FIXED
`SseRoute.kt` now uses `call.respondTextWriter(contentType = ContentType.Text.EventStream) { … }` and emits a `: ping\n\n` heartbeat every 15 s. Also sets `X-Accel-Buffering: no` so nginx-style proxies don't buffer.

### 4.3 SSE "silent gap" between `analyzing` and `notion_ticket` — ✅ PARTIALLY FIXED
`CrashProcessingQueue.progress(jobId, message, stage = "analyzing", level, metadata)` now exists. `Application.processJob` + `handlePostAnalysis` call it at: PII/fingerprint start, analysis complete (with severity+confidence), severity-gate skip, GitHub issue open, branch ready/failed, Notion start, Notion failure (with fallback). Agent sub-steps inside `CrashAnalysisAgent.process` are still opaque — add `queue.progress(...)` calls inside the agent itself for full coverage.

### 4.4 Live debugging without code changes
```bash
# All server stages
./gradlew :mba-server:run | grep -E "MBA/|CrashProcessingQueue|CrashOrchestrator"
# Just the agent
./gradlew :mba-server:run | grep -E "MBA/Agent|GeminiLLMCaller|MBA/PII|MBA/Fingerprint|MBA/DedupCache"
```
In `MBAMode.SdkOnly` logs go to Logcat (tag `MBA/Agent`).

## 5. Conventions

- Kotlin Multiplatform; common code under `src/commonMain/kotlin/`.
- Internal SDK glue (e.g., `AgentFactory`) is `internal` — never instructed users to import directly.
- **Logging: Kermit everywhere via `MBALog`** (`mba-core/.../MBALog.kt`). NEVER use `println`, `System.out/err.println`, `android.util.Log`, or `org.slf4j.LoggerFactory` directly — every module (SDK, server, sample app) routes through `MBALog.{d,i,w,e}(component, message)`. Tag convention: short component name (e.g. `Agent`, `Notion`, `PII`, `Server`, `ServerModule`, `JobStore`, `CrashProcessingQueue`, `RateLimiter`, `FileDedupPersistence`, `Sample`) — `MBALog` prefixes them with `MBA/`. Gated by `MBALog.enabled`: SDK flips it in `MBA.configure(config.debug)`; the server forces it on in `Application.main()`; the sample app turns it on before `MBA.configure` so the env-var banner is visible.
- Long-running suspend work is coordinated through `CrashProcessingQueue`; emit user-visible progress via SSE rather than blocking the booth.

## 6. Auto-fix flags & env vars

### SDK flags (per-crash, in `RawCrashReport`)
- `autoFix: Boolean = false` — opt-in to the GitHub auto-fix path. Severity gate still applies (HIGH/CRITICAL only).
- `skipNotion: Boolean = false` — skip Notion ticket creation. Combine with `autoFix=true` for a GitHub-only pipeline, or use alone for "dry-run" (analysis only).

Both are also wired into `MBAConfig.Builder` (`autoFix`, `skipNotion`) so apps can opt in once via `MBA.init { autoFix = true; skipNotion = true }`. `CrashWriter` (both JVM and Android `actual`) reads `MBA.requireConfig()` defensively and stamps the flags onto every persisted `RawCrashReport`.

### Server env vars
| Var | Required | Notes |
|-----|----------|-------|
| `GEMINI_API_KEY` | yes | Required for the analysis pipeline. |
| `NOTION_API_KEY` | optional | If blank, Notion is disabled. |
| `NOTION_DATABASE_ID` | optional | If blank, Notion is disabled. |
| `GITHUB_TOKEN` | optional | Needed for the auto-fix path (PAT or App token, `repo` scope). |
| `GITHUB_OWNER` | optional | Repo owner. Auto-fix disabled if blank. |
| `GITHUB_REPO` | optional | Repo name. Auto-fix disabled if blank. |
| `GITHUB_BASE_BRANCH` | optional | Default `main`. Branch the new `autofix/issue-N-<slug>` ref is cut from. |
| `MBA_SERVER_API_KEY` | optional | Reserved for future API-key auth. |
| `MBA_AUTOFIX_ENABLED` | optional | Legacy master switch read by `SeverityRouter.route()` (LOW-only). Independent of the new `shouldAutoFix(severity)` helper. |
| `PORT` | optional | Default `8080`. |
| `MBA_DATA_DIR`, `MBA_DEDUP_CACHE_PATH` | optional | Persistence locations. |

`mba-server/build.gradle.kts` now also forwards `GITHUB_TOKEN`/`GITHUB_OWNER`/`GITHUB_REPO`/`GITHUB_BASE_BRANCH` from `local.properties` to the `run` task.

### Routing truth table

| `autoFix` | `skipNotion` | Severity | GitHub configured | Notion configured | Outcome |
|-----------|--------------|----------|-------------------|-------------------|---------|
| false     | false        | any      | any               | yes               | Notion only (today's path) |
| false     | true         | any      | any               | any               | Dry-run (`analysis://<fp>` terminal) |
| true      | false        | HIGH/CRIT | yes              | yes               | GitHub issue+branch **and** Notion ticket |
| true      | true         | HIGH/CRIT | yes              | any               | GitHub issue+branch only |
| true      | any          | LOW/MED  | any               | yes               | Severity-gate warning + Notion only |
| true      | true         | LOW/MED  | any               | no                | Severity-gate warning + dry-run |

### GitHub auto-fix v1 (`GitHubAutoFixOpener`)
v1 only does **issue + tracking branch**. Sequence:
1. `POST /repos/{o}/{r}/issues` → returns `issue.number`, `issue.html_url`.
2. `GET /repos/{o}/{r}/git/ref/heads/{baseBranch}` → base SHA.
3. `POST /repos/{o}/{r}/git/refs` → creates `refs/heads/autofix/issue-{n}-{slug}`.

v2 (TODO): wire the Koog/Gemini patch loop into `GitHubPullRequestCreator.openFix` so the agent reads `report.file:line`, proposes a unified diff, commits to the v1 branch, and opens a **draft** PR. Guardrails in `GitHubPullRequestCreator` (max 20 diff lines, no new deps, no public-API changes, refuse `main`/`master` base, labels `mba/ai-generated`+`do-not-merge-yet`) already exist — only the LLM patch generation step is missing.
