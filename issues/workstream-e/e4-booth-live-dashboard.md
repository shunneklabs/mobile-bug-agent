# E4 — Live Koog progress dashboard at `/booth` (#36)

Kickoff scaffold for Workstream E, Issue E4.

## Goal
- New route `GET /booth` serving a polished hero + terminal + pipeline UI.
- Consumes existing `/events` SSE feed.
- Shows live job state (QUEUED → ANALYZING → TICKET_CREATED / PR_OPENED).
- Reads tool-level step events from B2 contract enrichment.

## Acceptance criteria
Tracked in GitHub issue #36.

## Parent
- Parent PR: #37 (`workstream-e-parent` → `master`)
- Tracked issue: #36
