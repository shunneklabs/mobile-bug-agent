# Workstream B + D Parent Branch

This branch is the umbrella integration branch for the KotlinConf Munich demo readiness work on:

- **Workstream B**: Ktor server pipeline hardening (boundaries, SSE/job contract, D integration).
- **Workstream D**: Koog tools, severity router, demo orchestration, auto-fix path.

## Tracked child issues

| Issue | Title |
| --- | --- |
| #38 | B1: Harden `/report` auth boundaries + booth-safe CORS/rate-limits |
| #39 | B2: Enrich job/SSE event contract for booth timeline and artifacts |
| #40 | D1: Implement `DemoOrchestrator` + `DemoEventSink` and wire server worker |
| #41 | D2: Add Koog tool wrappers (Notion/GitHub/source/fix/guardrails/PR/link-back) |
| #42 | D3: Deterministic fixture + unit tests (notify-only / auto-fix / guardrail fallback) |

## Merge policy

- Child PRs target this branch (`workstream-bd-parent`).
- Child PRs are **squash-merged** into the parent.
- Final parent PR will be opened against `master` once B+D close the demo loop.
- Never merge to `main`/`master` automatically.

## Demo north star (loop this branch must close)

1. `POST /report` accepts crash → returns `202` + jobId.
2. Worker delegates to `DemoOrchestrator`.
3. Severity router selects notify-only or auto-fix path.
4. Koog tool wrappers emit tool-level SSE events.
5. Guardrails decide PR vs Notion fallback.
6. Booth TV shows live transitions through `QUEUED → ANALYZING → TICKET_CREATED|PR_OPENED`.
