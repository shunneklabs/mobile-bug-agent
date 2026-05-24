# Koog Agent Roadmap

This document tracks what Mobile Bug Agent already does with Koog and what we plan to build next for the real auto-fix agent.

## Latest Branch Status

Issue `#79` tracks the KMP structure direction. The repo should keep current
Android SDKOnly behavior stable while making common source sets safe for future
iOS and Web/Wasm adapters.

Current direction:

- Keep module names for now.
- Keep `mba-core` as shared KMP models/config/capture primitives.
- Keep `mba-agent/commonMain` focused on pure crash-analysis contracts,
  pipeline logic, prompts, models, sink contracts, and local orchestration.
- Keep Koog provider clients, legacy HTTP callers, and JVM closeable resources
  in Android/JVM source sets instead of `commonMain`.
- Keep `mba-ios` and `mba-web` as honest future scaffolds until real capture
  strategies exist.

### Mapping JetBrains' 2026 default structure to MBA

JetBrains' newer KMP default separates shared libraries from runnable platform
apps. MBA is SDK-first rather than app-first, so the mapping is:

```text
shared/core library code
→ mba-core
→ mba-agent/commonMain

platform runtime adapters
→ mba-android
→ mba-ios
→ mba-web
→ mba-jvm

sample/runnable app modules
→ mba-sample

server-side product
→ mba-server

optional integrations
→ mba-github
→ mba-notion
```

This gives each module one responsibility without forcing a large directory
move before the platform adapters are real. A future rename to folders such as
`core/`, `agent/`, `platform/*`, `integrations/*`, `apps/*`, and `server/`
should be a mechanical follow-up, not a prerequisite for the current SDK
architecture.

## Current Koog Status

Koog is already wired into the core crash-analysis path in `mba-agent`.

### What we have done so far

- Added Koog `0.8.0` to the Gradle version catalog as `koog-agents`.
- Made `AgentFactory` default to the Koog path through `useKoog = true`.
- Added `KoogAgentFactory` to create Koog-backed prompt executors for Android
  and JVM runtime variants.
- Split `CrashAnalysisExecutorFactory` / `CrashAnalysisExecutor` into
  `commonMain` so pure SDKOnly orchestration does not depend on Koog, Ktor
  clients, or JVM classes.
- Added provider/model support for SDKOnly through Koog:
  - Gemini through Koog `GoogleLLMClient`.
  - OpenAI through Koog `OpenAILLMClient`.
  - Anthropic, Ollama/local, OpenRouter, Mistral, DeepSeek, DashScope, and
    OpenAI-compatible custom endpoints through the matching Koog clients.
- Kept the old direct HTTP LLM callers behind a rollback/debug flag.
- Added `KoogCrashAnalysisExecutor` for the three main crash-analysis steps:
  - Parse sanitized stack trace into `ParsedStackTrace`.
  - Classify severity into `SeverityResult`.
  - Generate title, description, possible cause, and repro summary as `CrashSummary`.
- Added JSON extraction around Koog responses so the agent can recover JSON payloads even when the model returns extra text.
- Kept PII scrubbing before the Koog call.
- Kept crash fingerprinting and local dedup before the Koog call.
- Kept fallback `ProcessedCrashReport` creation when Koog/AI analysis fails.
- Kept duplicate-crash behavior so duplicate reports skip LLM work during automatic processing.
- Wired the server queue so `/report` jobs can run crash analysis and publish progress to the booth UI.
- Added operator controls in `/booth?debug=1` for:
  - `Notion Ticket`
  - `GitHub Issue`
  - `Both`
  - `Autofix`
  - `Notify`
  - `Fallback`
- Wired operator decisions so Koog/AI analysis produces the report content, while backend integrations create Notion tickets and GitHub issues.
- Fixed duplicate/fallback operator paths so GitHub/Notion actions can still create tickets even when Koog is skipped or fails.
- Added GitHub runtime configuration loading for `GITHUB_TOKEN`, owner/repo aliases, and base branch.
- Added local/dev rate-limit bypass so booth debug work does not spam `429` errors while developing locally.
- Hardened `/report` server boundaries:
  - API-key authentication is scoped to `POST /report`.
  - Read-only booth/status endpoints remain accessible for the booth demo.
  - CORS is restricted to explicit allowed origins.
  - Rate limiting is scoped to `/report` while localhost/dev bypass remains available.
- Enriched booth/SSE events with structured `step` and `artifactType` metadata so the UI can distinguish GitHub issues, PRs, Notion tickets, duplicate reports, analysis artifacts, and generic links.
- Added Koog auto-fix tool-wrapper contracts in `mba-agent` for:
  - Notion ticket creation.
  - GitHub issue creation.
  - Source file reading.
  - Deterministic fix suggestion for the demo NPE.
  - Guardrail checks.
  - Pull request opening.
  - PR/ticket/issue link-back payloads.
- Added deterministic test fixtures for:
  - `demo-npe-low` auto-fix eligible route.
  - `critical-crash` notify-only route.
  - `low-guardrail-fail` fallback/guardrail route.
- Updated auto-fix routing so only `LOW` severity can enter auto-fix when `MBA_AUTOFIX_ENABLED=true`; higher severities and disabled auto-fix remain notify-only.

### Current dependency placement

```text
mba-agent/commonMain
→ crash-analysis contracts
→ pure processing pipeline
→ prompts and serializable analysis models
→ sink/runtime models

mba-agent/androidMain and mba-agent/jvmMain
→ AgentFactory
→ KoogAgentFactory
→ Koog prompt clients
→ legacy direct HTTP callers
→ Ktor OkHttp client engine
```

This follows the current Kotlin Multiplatform direction: common code should not
accidentally require platform APIs that future iOS or Web/Wasm targets cannot
compile.

## Current Boundary

Koog is currently the crash-analysis brain. It understands and summarizes the crash, but it does not yet directly create tickets or patch code.

Current production flow:

```text
Crash report
→ PII scrub
→ fingerprint + dedup
→ Koog crash analysis when needed
→ ProcessedCrashReport
→ Notion API and/or GitHub API integration
→ booth progress events
```

Current operator `Autofix` flow:

```text
Operator clicks Autofix
→ analyze or build fallback report
→ create GitHub issue
→ create/prep autofix branch
→ inform booth that branch is ready
→ stop
```

Current Koog auto-fix foundation:

```text
ProcessedCrashReport
→ tool wrapper input/output contracts
→ source read contract
→ deterministic demo NPE patch proposal
→ guardrail result
→ PR open/link-back contract
```

It does not yet do the full real-agent loop:

```text
checkout code
→ inspect repo
→ plan patch
→ edit files
→ run tests/build
→ push branch
→ create draft PR
→ report PR/result to booth
```

## Future Plan

### Phase 1: Real Auto-Fix Runner

- Add a server-side `GitHubAutoFixRunner` or equivalent service.
- Trigger it from the existing `Autofix` operator path after the GitHub issue and branch are created.
- Connect it to the new Koog tool-wrapper contracts added in `mba-agent`.
- Use stable backend credentials and normal GitHub API/git operations, not a local operator MCP session as the required production dependency.
- Clone or checkout the target repository into an isolated workspace.
- Checkout a branch tied to the GitHub issue.
- Publish each step to `/booth` as live progress.

### Phase 2: Koog Patch Planner

- Give Koog the processed crash report, stack trace, issue context, and selected source files.
- Ask Koog to identify likely files, root cause, and the smallest safe patch plan.
- Keep the deterministic demo NPE patch path available for predictable booth demos.
- Require structured output such as:
  - root cause hypothesis
  - files to inspect
  - files to edit
  - test strategy
  - risk level
- Keep deterministic guardrails outside Koog for severity, repo access, branch names, and allowed commands.
- Keep the `MBA_AUTOFIX_ENABLED` kill switch outside Koog.

### Phase 3: Code Editing Agent

- Add a patch application layer that can safely edit files in the checked-out workspace.
- Prefer small, reviewable diffs.
- Keep a hard limit on changed files and patch size for demo safety.
- Reject edits outside the checkout or outside allowed project paths.
- Preserve style by following the surrounding code.

### Phase 4: Verification Loop

- Run targeted tests first when the crash maps to a known module.
- Run broader module tests after the targeted fix passes.
- Run build checks before opening a PR.
- Capture logs and failure summaries.
- If tests fail, let Koog attempt a limited number of repair iterations.
- Stop safely and report failure to booth when the fix cannot be verified.

### Phase 5: Draft Pull Request

- Push the autofix branch only after verification succeeds.
- Create a draft PR against `master` or the configured base branch.
- Link the PR back to the generated GitHub issue.
- Add crash fingerprint, severity, affected screen, root cause, tests run, and booth job ID to the PR body.
- Publish the PR URL and final status back to `/booth`.

### Phase 6: Notion/GitHub/Booth Polish

- Update Notion tickets with GitHub issue/PR links when both integrations are selected.
- Add more granular booth statuses for clone, plan, patch, test, push, and PR creation.
- Persist enough job state so booth can show the full auto-fix history after refresh.
- Add clear failure states for missing GitHub config, failed clone, failed tests, failed push, or PR creation failure.
- Surface `step` and `artifactType` consistently in booth cards/timeline views.

## Remaining Gap After Issues #41/#42

Issues `#41` and `#42` added the safe tool/foundation layer, but they did not yet implement the long-running runner that changes real repository files.

Still needed:

- A workspace manager for cloning/checking out the target repository.
- A patch application layer that writes the proposed changes to disk.
- Test/build execution with captured logs.
- Limited repair retries when verification fails.
- Branch push after verification.
- Draft PR creation only after tests/build pass.
- Final booth event with PR URL or failure logs.

## Recommended Architecture

Use Koog as the reasoning layer and backend services as the execution layer.

```text
Koog
  - analyze crash
  - plan fix
  - explain root cause
  - suggest patch/test strategy

Backend runner
  - clone repo
  - read/write files
  - run Gradle/tests
  - call GitHub API
  - create draft PR
  - update booth/Notion/GitHub status
```

This keeps the demo reliable: Koog makes decisions, but the server owns credentials, filesystem operations, command execution, and GitHub/Notion API calls.

## Safety Guardrails To Keep

- Do not auto-fix without explicit `RawCrashReport.autoFix` or operator `Autofix` action.
- Keep severity routing separate from the LLM.
- Keep PII scrubbing before any model call.
- Keep dedup so repeated crashes do not waste LLM calls.
- Keep fallback ticket creation when Koog fails.
- Do not push or open PRs unless tests/build pass.
- Do not depend on local MCP for production/server automation.
- Log and show every auto-fix step in booth.
