# Contributing to Mobile Bug Agent (MBA)

Thanks for helping MBA turn mobile crashes into useful engineering work. This repo is still alpha, so small, focused PRs with clear tests are best.

## Current priorities

For the KotlinConf Munich demo, highest-value work is:

1. **Koog orchestration** — tool wrappers, routing, deterministic tests, visible SSE tool events.
2. **Booth polish** — one-button sample crash path, QR, branding, fallback recording.
3. **Repo polish** — public docs, issue templates, release tag, demo media.

Please avoid spreading effort into new backends or large SDK scope unless an issue already calls for it.

## Why Koog

MBA uses [JetBrains Koog](https://github.com/JetBrains/koog) as its AI agent runtime. Koog is a Kotlin-native framework for building predictable, fault-tolerant AI agents across JVM, Android, iOS, and more. We chose Koog because:

- **Kotlin-first**: Idiomatic Kotlin DSL, no Java wrappers
- **Multiplatform**: Same agent code runs on Android, iOS, and server
- **Built-in tooling**: Tool calling, structured output, retries, and history compression out of the box
- **JetBrains ecosystem**: First-class support for KotlinConf demos and JetBrains audiences

See `mba-agent/src/commonMain/kotlin/dev/sunnat629/mba/agent/KoogAgentFactory.kt` for the Koog integration point.

## Getting started

1. Clone the repo
2. Open in Android Studio or IntelliJ IDEA
3. Run targeted tests for the module you changed
4. Run `./gradlew allTests` before opening a PR when your change affects shared code

## Project Structure

| Module | Purpose |
| --- | --- |
| `mba-core` | Shared models, PII sanitizer, fingerprinting, dedup cache |
| `mba-agent` | AI crash analysis via Koog (Gemini/OpenAI) |
| `mba-android` | Android SDK entry point, WorkManager integration |
| `mba-jvm` | JVM SDK entry point |
| `mba-notion` | Notion ticket backend |
| `mba-github` | GitHub ticket backend + auto-fix opener + guard-railed PR creator |
| `mba-server` | Ktor server for crash ingestion and SSE feed |
| `mba-sample` | Demo app for crash-button smoke tests |

## Development workflow

- Keep PRs focused on one module or one workstream when possible.
- Add or update tests for behavior changes.
- For bug fixes, include a reproduction test when practical.
- For docs-only changes, no build is required unless commands or code snippets changed.
- Do not commit secrets. Use `local.properties`, environment variables, or ignored `.env` files for local credentials.

## Good First Issues

Look for issues labeled `good first issue` on our [issue tracker](https://github.com/shunneklabs/mobile-bug-agent/issues).

## Code Style

- Kotlin with `explicitApi()` on public modules
- Follow existing naming conventions
- Keep public API additions small and documented
- Prefer targeted module tests during development, then `./gradlew allTests` before PR if shared behavior changed

## Reporting Issues

- Search [existing issues](https://github.com/shunneklabs/mobile-bug-agent/issues) first.
- For bugs: include MBA version, target platform (Android API level / JVM), repro steps, expected vs actual, and a minimal stack trace if relevant.
- For security issues: **do not open a public issue.** Email the maintainer or use GitHub's private security advisory.

## Pull Requests

1. Fork and create a feature branch off the current default branch.
2. Keep PRs focused — one logical change per PR.
3. Add or update tests for behavior changes.
4. Run `./gradlew allTests` locally; CI must be green.
5. Reference the issue number in the PR description (e.g. `Fixes #123`).
6. By submitting, you agree your contribution is licensed under Apache 2.0.

## License

Apache 2.0 — see [LICENSE](LICENSE). All contributions are licensed under the same terms.
