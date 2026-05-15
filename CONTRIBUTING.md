# Contributing to Mobile Bug Agent (MBA)

Thanks for your interest in MBA! This document covers the basics for getting started.

## Why Koog

MBA uses [JetBrains Koog](https://github.com/JetBrains/koog) as its AI agent runtime. Koog is a Kotlin-native framework for building predictable, fault-tolerant AI agents across JVM, Android, iOS, and more. We chose Koog because:

- **Kotlin-first**: Idiomatic Kotlin DSL, no Java wrappers
- **Multiplatform**: Same agent code runs on Android, iOS, and server
- **Built-in tooling**: Tool calling, structured output, retries, and history compression out of the box
- **JetBrains ecosystem**: First-class support for KotlinConf demos and JetBrains audiences

See `mba-agent/src/commonMain/kotlin/dev/sunnat629/mba/agent/KoogAgentFactory.kt` for the Koog integration point.

## Getting Started

1. Clone the repo
2. Open in Android Studio or IntelliJ
3. Run `./gradlew allTests` to verify everything builds

## Project Structure

| Module | Purpose |
| --- | --- |
| `mba-core` | Shared models, PII sanitizer, fingerprinting, dedup cache |
| `mba-agent` | AI crash analysis via Koog (Gemini/OpenAI) |
| `mba-android` | Android SDK entry point, WorkManager integration |
| `mba-jvm` | JVM SDK entry point |
| `mba-notion` | Notion ticket backend |
| `mba-server` | Ktor server for crash ingestion and SSE feed |
| `mba-sample` | Demo app with crash button |

## Good First Issues

Look for issues labeled `good first issue` on our [issue tracker](https://github.com/shunneklabs/mobile-bug-agent/issues).

## Code Style

- Kotlin with `explicitApi()` on public modules
- Follow existing naming conventions
- Run `./gradlew allTests` before submitting PRs

## License

Apache 2.0 — see [LICENSE](LICENSE).
