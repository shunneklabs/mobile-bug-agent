# MBA TODO (working backlog)

This file tracks what is still missing for the **SDK-only, Notion-powered MVP**.

Goal: **Crash the sample app → reopen → Notion Crash Report + Bug Ticket created in < 30 seconds**.

> Branch note: this TODO reflects the current working branch `feat/mba-agent-gemini`.

---

## ✅ Done (high level)
- KMP multi-module repo scaffold (`mba-core`, `mba-android`, `mba-agent`, `mba-notion`, `mba-server`, `mba-sample`)
- Crash capture plumbing (Android/JVM CrashWriter actuals + crash handler wiring)
- `mba-agent` models + prompts + analysis pipeline scaffold (Gemini-ready)
- `mba-notion` basics: `NotionClient`, models, `NotionTicketBackend` (creates a ticket page)
- `mba-server` MVP `/report` endpoint (optional; not required for SDK-only MVP)
- `PHASES.md` created (roadmap/phase plan)

---

## 🔴 MVP blockers (must do next)

### 1) Notion Crash Store (Crash Reports DB)
Implement `mba-notion/NotionCrashStore.kt` as `CrashStore`:
- [ ] `findByFingerprint(fingerprint)` → query Crash Reports DB
- [ ] `insertCrash(report)` → create Crash Report row
- [ ] `incrementCount(groupId, device)` → occurrence count + device/os sets + last seen
- [ ] `linkTicket(groupId, ticketId)` → relation Crash Report ↔ Bug Ticket

Also required in `NotionClient`:
- [ ] `updatePage(pageId, properties)` (PATCH `/v1/pages/{page_id}`)

### 2) Android next-launch processing
Implement real logic in `mba-android/PendingCrashProcessor`:
- [ ] List pending crash files under `filesDir/mba-crashes`
- [ ] Deserialize `RawCrashReport`
- [ ] Run `CrashAnalysisAgent`
- [ ] Dedup + persistence:
  - [ ] new crash → create Crash Report + create Bug Ticket + link
  - [ ] duplicate → update Crash Report (increment count + device matrix)
- [ ] Delete/mark processed crash file only after successful Notion sync

### 3) WorkManager reliability
Make `CrashUploadWorker` actually do “process + sync” with retries:
- [ ] exponential backoff
- [ ] offline handling
- [ ] rate limit friendly Notion calls

---

## 🟠 MVP quality improvements (after blockers)

### Koog + Gemini Flash wiring (replace heuristics)
- [ ] Add `AgentFactory` + Koog executor integration
- [ ] Enforce strict JSON tool outputs (decode with kotlinx-serialization)
- [ ] Add test fixtures: 10+ real stack traces for parser/classifier/summary

### Notion schema alignment
- [ ] Confirm/standardize Notion property names used by backends (Crash Reports DB + Bug Tickets DB)
- [ ] Add Notion database template export instructions (README)

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
