# MBA TODO (working backlog)

This file tracks what is still missing for the **SDK-only, Notion-powered MVP**.

Goal: **Crash the sample app → reopen → Notion Crash Report + Bug Ticket created in < 30 seconds**.

> Branch note: this TODO reflects the current working branch `feat/mba-agent-gemini`.

---

## ✅ Done (high level)
- KMP multi-module repo scaffold (`mba-core`, `mba-android`, `mba-agent`, `mba-notion`, `mba-server`, `mba-sample`)
- Crash capture plumbing (Android/JVM CrashWriter actuals + crash handler wiring)
- `mba-agent` models + prompts + analysis pipeline scaffold (Gemini-ready)
- `mba-notion`:
  - `NotionClient` (create + query + **updatePage**)
  - Notion models (create/query/update)
  - `NotionTicketBackend` (creates a ticket page)
  - **`NotionCrashStore` (CrashStore impl) — MVP**
- Android next-launch processing:
  - **`PendingCrashProcessor` implemented** (disk → agent → CrashStore + TicketBackend)
  - Architecture: app injects CrashStore/TicketBackend (app owns secrets)
- `PHASES.md` created (roadmap/phase plan)

---

## 🔴 MVP blockers (must do next)

### 1) Notion Crash Store correctness (dedup + counters + relations)
Current status: implemented, but needs finishing touches for a *real* Notion schema.

- [ ] Properly support **Occurrence Count** increment
  - Add a Notion API read path to fetch current property values OR store count locally and push updates
- [ ] Properly support **Bug Ticket relation** property
  - Use Notion relation JSON shape (instead of storing ticketId as rich text)
- [ ] Device/OS matrix
  - Currently written as rich text to avoid multi-select option management
  - Decide: keep as text (MVP) or manage multi-select options

### 2) Android processing lifecycle + reliability
- [ ] Call `PendingCrashProcessor.process(...)` after `MBA.configure(...)` in the host app (sample app will demonstrate)
- [ ] Ensure crash-file deletion is safe:
  - If Notion sync fails, keep file for retry
  - If file is corrupted, move aside (do not loop forever)
- [ ] Rate limiting / retries for Notion API (3 req/sec)

### 3) WorkManager reliability (recommended for MVP)
- [ ] Wire `CrashUploadWorker` to run `PendingCrashProcessor` in background
- [ ] exponential backoff
- [ ] offline handling

---

## 🟠 MVP quality improvements (after blockers)

### Koog + Gemini Flash wiring (replace heuristics)
- [ ] Add `AgentFactory` + Koog executor integration
- [ ] Enforce strict JSON tool outputs (decode with kotlinx-serialization)
- [ ] Add test fixtures: 10+ real stack traces for parser/classifier/summary

### Notion schema alignment
- [ ] Confirm/standardize Notion property names used by backends:
  - Crash Reports DB: Fingerprint, Severity, Occurrence Count, Stack Trace, etc.
  - Bug Tickets DB: Title, Severity, Description, Steps, etc.
- [ ] Provide duplicatable Notion DB templates + setup guide

### PII + security
- [ ] Expand PII scrubber patterns (emails, tokens, phone, IPs)
- [ ] Ensure scrub happens before any network call

---

## 🟡 Demo app (mba-sample)
- [ ] `SampleApplication.kt` (SDK install + configure)
- [ ] `MainActivity.kt` (Compose Material 3, edge-to-edge)
- [ ] `CrashScenarios.kt` (NPE, IllegalState, OOM, non-fatal)
- [ ] Dashboard UI: pending crashes, processed count, last ticket URL
- [ ] Adaptive icon assets

---

## 🔵 Optional / later (out of SDK-only MVP)
- [ ] iOS crash capture + persistence
- [ ] Semantic dedup (embeddings / SmartFingerprint)
- [ ] MBA Cloud: hosted ingestion, dashboard, alerts
- [ ] Auto-fix PR generation

---

## Notes
- `mba-server` exists but **SDK-only MVP does not require it**.
- Keep `mba-core` dependency direction clean: core has interfaces + models; platform/backends implement them.
