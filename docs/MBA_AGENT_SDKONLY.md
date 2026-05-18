# SDKOnly Mode

SDKOnly mode lets an app run Mobile Bug Agent without sending crashes to the
hosted MBA backend. The app captures crashes, processes them locally, receives
callbacks or JSON payloads, and can optionally send results to Notion or GitHub
using app-owned credentials.

This mode is useful when a team wants the SDK to act as a standalone crash
agent inside the application.

## What SDKOnly Provides

In SDKOnly mode, Mobile Bug Agent can:

- capture fatal JVM/Kotlin crashes
- record explicitly logged non-fatal errors
- detect supported Android ANR exits after the next app start
- build a structured raw crash report
- run Koog/LLM crash analysis when configured
- fall back to a raw technical report when analysis is disabled or fails
- group repeated crashes by app, environment, and fingerprint
- emit the latest processed event to app callbacks
- emit a batch event containing all processed crashes from a worker run
- optionally create or update Notion and GitHub records

The app controls the runtime mode, LLM configuration, callbacks, and optional
external integrations.

## Installation Shape

A typical Android app depends on the Android adapter:

```kotlin
implementation(project(":mba-android"))
```

Optional external delivery modules are added only when the app needs them:

```kotlin
implementation(project(":mba-notion"))
implementation(project(":mba-github"))
```

The Android adapter brings in the shared core and local agent pieces it needs.
Apps do not need to add each lower-level module separately when consuming the
published Android SDK.

## Runtime Flow

```text
App crash, ANR exit, or logged non-fatal error
        |
        v
MBA writes RawCrashReport JSON to app-private storage
        |
        v
On the next worker run, pending crash files are read
        |
        v
SDKOnly pipeline processes each crash
        |
        +-- Koog/LLM analysis, if enabled and configured
        |
        +-- raw fallback, if analysis is disabled or fails
        |
        v
Local aggregation creates or updates one bug group
        |
        v
Optional Notion/GitHub sinks create or update external records
        |
        v
App receives latest callback and optional batch callback/flow
```

The SDK does not run network uploads or LLM analysis inside the fatal crash
handler. During a fatal crash, the reliable action is to write the raw crash
snapshot to disk. Processing happens later, after the app starts again.

## Agent Analysis

When local agent analysis is enabled and an LLM key is configured, the SDK runs
Koog against the crash-context payload and produces a richer report:

- title
- description
- severity
- confidence
- steps to reproduce
- possible cause
- parsed crash file, line, and method when available
- app-code classification
- sanitized crash message, stack trace, and breadcrumbs
- fingerprint

The raw crash is still available in the callback event. This lets the app show
or forward the original technical context when needed.

## Raw Fallback

Raw fallback is used when:

- local agent analysis is turned off
- no LLM key is available
- the configured LLM key is invalid
- Koog/LLM analysis fails
- the app intentionally wants non-agentic crash payloads

Fallback reports are still useful. They include exception type, message, stack
trace, app version, build type, device model, OS version, screen name when set,
breadcrumbs when added, custom metadata when provided, fingerprint, and local
grouping details.

Fallback events are marked as non-agentic so the app can tell the difference
between analyzed reports and raw-derived reports.

## Duplicate Grouping

SDKOnly grouping uses:

```text
appId + environment + fingerprint
```

The first occurrence creates a local bug group. Later occurrences with the same
grouping key update that group instead of creating a second parent bug.

The app can still receive every processed occurrence through the batch callback
or batch flow. The default latest callback is optimized for the newest event
from the worker run.

## Callbacks and JSON Payloads

Apps can receive processed results as Kotlin objects or JSON strings.

Use the latest callback when the app only needs the newest processed event:

```kotlin
MBAAndroid.saveConfig(
    context = app,
    callback = { event ->
        // event.latest processed crash result
    },
)
```

Use the batch callback when the app needs all processed crashes from the worker
run:

```kotlin
MBAAndroid.saveConfig(
    context = app,
    batchCallback = { batch ->
        // batch.events contains every processed event from this run
    },
)
```

JSON callbacks are useful when the host app wants to hand the payload to its own
pipeline, backend, logging system, or custom ticketing workflow.

## Optional Notion and GitHub Delivery

Notion and GitHub are optional modules. They are not required for SDKOnly mode.

When enabled, the SDK sends the processed or raw-fallback result to the
registered sinks. The app owns those credentials and decides whether to enable
one integration, both integrations, or neither.

Duplicate crashes should update the existing grouped record instead of creating
new parent tickets for the same bug group.

## Privacy Boundary

SDKOnly mode analyzes crash-context data only. It does not inspect app
databases, screen contents, contacts, photos, location, network traffic,
cookies, or user sessions.

Crash context can still contain sensitive values if the host app puts those
values in exception messages, stack traces, breadcrumbs, screen names, ANR
traces, or custom metadata. Treat crash context like logs: avoid adding secrets
or raw personal data.

See `MONITORING_SECURITY_CLAIMS.md` for the complete monitoring and privacy
boundary.
