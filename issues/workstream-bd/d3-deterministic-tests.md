# D3 — Deterministic fixtures + orchestrator tests

Tracks: #42
Parent: `workstream-bd-parent` (PR #43)

## Fixtures
- `fixtures/demo-npe-low.json` — stable NPE → auto-fix PR path.
- `fixtures/critical-crash.json` — critical → Notion ticket only.
- `fixtures/low-guardrail-fail.json` — low severity but guardrail fails → Notion fallback.

## Tests
- `SeverityRouterTest` — all enum values.
- `DemoOrchestratorNotifyOnlyTest`.
- `DemoOrchestratorAutoFixTest`.
- `DemoOrchestratorGuardrailFallbackTest`.
- `DemoEventSinkTest` — every step emits.

## Files (planned)
- `mba-agent/src/test/resources/fixtures/*.json`
- `mba-agent/src/test/kotlin/dev/sunnat629/mba/agent/orchestrator/*Test.kt`

## Acceptance
- Tests run hermetically (no live Notion/GitHub).
- All 3 paths verified end-to-end against fake tool implementations.
- `MBA_AUTOFIX_ENABLED=false` short-circuits to Notion path.
