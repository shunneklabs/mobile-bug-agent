# D2 — Koog tool wrappers

Tracks: #41
Parent: `workstream-bd-parent` (PR #43)

## Wrappers to add
- `CreateNotionTicketTool` — wraps `NotionTicketBackend`.
- `CreateGitHubIssueTool` — wraps `GitHubIssueBackend`.
- `ReadSourceFileTool` — wraps `GitHubSourceReader`.
- `SuggestFixTool` — deterministic patch for known demo NPE; Koog suggestion otherwise.
- `RunGuardrailsTool` — base-branch / one-file / <20 lines / no-deps / no-public-API / kill-switch.
- `OpenPullRequestTool` — wraps `GitHubPullRequestCreator`; never targets `main`/`master`.
- `LinkPullRequestBackTool` — updates job state + Notion/GitHub artifacts.

## Files (planned)
- `mba-agent/src/main/kotlin/dev/sunnat629/mba/agent/tools/*Tool.kt`
- `mba-agent/src/main/kotlin/dev/sunnat629/mba/agent/orchestrator/DemoOrchestrator.kt` (wire-up)

## Acceptance
- Each tool exposes Koog-compatible signature.
- Each tool emits one `DemoEventSink.emit(...)` on success and one on failure.
- Guardrails refuse `main`/`master` and oversized diffs.
