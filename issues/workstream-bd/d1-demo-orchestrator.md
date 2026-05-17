# D1 — DemoOrchestrator + DemoEventSink + server worker wiring

Tracks: #40
Parent: `workstream-bd-parent` (PR #43)

## Scope
- New `DemoOrchestrator` driving end-to-end job handling: analysis → severity → notify-only OR auto-fix.
- New `DemoEventSink` bridging tool/step progress to SSE.
- Replace inline Notion ticket creation in `CrashProcessingQueue` worker with `orchestrator.process(job)`.
- Catch-all fallback to notify-only on any tool failure.

## Files (planned)
- `mba-agent/src/main/kotlin/dev/sunnat629/mba/agent/orchestrator/DemoOrchestrator.kt`
- `mba-agent/src/main/kotlin/dev/sunnat629/mba/agent/orchestrator/DemoEventSink.kt`
- `mba-server/src/main/kotlin/dev/sunnat629/mba/server/jobs/CrashProcessingQueue.kt`
- `mba-server/src/main/kotlin/dev/sunnat629/mba/server/di/ServerModule.kt`

## Acceptance
- Worker no longer calls Notion directly; goes through orchestrator.
- Orchestrator routes by severity.
- Every tool step calls `DemoEventSink.emit(...)` and reaches `/events`.
- On unexpected error, job ends as `TICKET_CREATED` (fallback) or `FAILED` with reason.
