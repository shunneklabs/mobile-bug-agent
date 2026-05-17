# B2 — Enrich job/SSE event contract

Tracks: #39
Parent: `workstream-bd-parent` (PR #43)

## Scope
- Extend SSE payload: `message`, `step`, `artifactType`, `artifactUrl`.
- `artifactType` enum: `notion_ticket | github_issue | pull_request | duplicate | error`.
- Persist new fields in `JobStore` so reload preserves timeline.
- Update TV page consumer to render `message` + `artifactUrl`.

## Files (planned)
- `mba-server/src/main/kotlin/dev/sunnat629/mba/server/jobs/*`
- `mba-server/src/main/kotlin/dev/sunnat629/mba/server/routes/EventsRoute.kt`
- `mba-server/src/main/resources/static/booth.js`

## Acceptance
- SSE emits enriched fields on every state transition.
- TV timeline shows clickable artifact URL.
- Restart preserves enriched event history.
