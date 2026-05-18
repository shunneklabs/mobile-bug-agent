# MBA Agent SDKOnly Owner Notes

This document explains what `mba-agent` owns in SDKOnly mode, how it works with
`mba-android`, and what app developers should expect from callbacks, raw
fallback, local aggregation, and optional Notion/GitHub sinks.

Scope: SDKOnly only. Hosted backend behavior is intentionally out of scope.

## Mental Model

In SDKOnly mode, the app ships the crash agent locally. The app owns keys,
runtime settings, optional integrations, and callback handling.

`mba-agent` is the processing brain. It does not capture Android crashes and it
does not know about Android WorkManager. It receives a `RawCrashReport`, turns it
into an analyzed or fallback `ProcessedCrashReport`, groups it locally, and
returns an `MBAAgentEvent`.

`mba-android` is the Android adapter. It captures pending crash files, reads
runtime config, builds the agent pipeline, processes every pending crash, and
publishes app-facing callbacks/flows.

Optional modules such as `mba-notion` and `mba-github` provide ticket backends.
They are not dependencies of `mba-agent` or `mba-android` unless the app chooses
to add and register them.

## Module Responsibilities

### `mba-core`

Owns shared data and capture primitives:

- `RawCrashReport`
- `ProcessedCrashReport`
- `DeviceContext`
- `Severity`
- `CrashFingerprint`
- `CrashReportBuilder`
- `MBAConfig`
- `MBAMode`
- `LLMConfig`
- platform crash writing through `CrashWriter`

It does not run Koog and does not create tickets.

### `mba-agent`

Owns SDKOnly processing contracts and orchestration:

- Koog/LLM crash analysis through `CrashAnalysisAgent`
- SDKOnly orchestration through `SdkOnlyCrashOrchestrator`
- non-agentic raw fallback through `LocalFallbackCrashOrchestrator`
- delivery ordering through `CrashDeliveryPipeline`
- local aggregation contracts:
  - `LocalCrashAggregationStore`
  - `LocalBugGroup`
  - `LocalCrashOccurrence`
- app-facing event contracts:
  - `MBAAgentEvent`
  - `MBAAgentBatchEvent`
  - `MBAAgentCallback`
  - `MBAAgentBatchCallback`
- generic optional sink contract:
  - `MBAAgentSink`
  - `MBAAgentSinkResult`
- generic ticket backend bridge:
  - `TicketBackendAgentSink`

It does not depend directly on Notion, GitHub, Android, or WorkManager.

### `mba-android`

Owns Android runtime wiring:

- `MBAAndroid.install(context)`
- `MBAAndroid.saveConfig(...)`
- `CrashUploadWorker`
- reading pending crash files
- Android file-backed local aggregation store
- publishing:
  - `agentEvents`
  - `agentEventJson`
  - `agentEventBatches`
  - `agentBatchEventJson`
  - latest callback
  - batch callback
  - JSON callbacks

It does not contain Notion or GitHub clients.

### `mba-notion` and `mba-github`

Own optional external delivery:

- Notion ticket creation/update
- GitHub issue creation/update

The app registers these with `MBAAndroid.setTicketBackends(...)` or
`MBAAndroid.setExternalSinks(...)`.

## SDKOnly Runtime Flow

High-level flow:

```text
App crash / logged error
        |
        v
mba-core CrashWriter writes RawCrashReport JSON to disk
        |
        v
mba-android CrashUploadWorker starts on next app run
        |
        v
PendingCrashProcessor reads all mba_crash_*.json files
        |
        v
CrashDeliveryPipeline processes each raw crash
        |
        +-- if hosted upload is configured: try backend /report first
        |
        +-- if SDKOnly agent enabled: run Koog via SdkOnlyCrashOrchestrator
        |
        +-- if Koog disabled, missing, or fails: run LocalFallbackCrashOrchestrator
        |
        v
LocalCrashAggregationStore upserts group and occurrence
        |
        v
Optional Notion/GitHub sinks sync external artifacts
        |
        v
Worker deletes successfully processed crash file
        |
        v
MBAAndroid publishes latest callback + optional batch callback/flows
```

Important: the worker processes all pending crash files so old crashes are not
lost. The default app callback is not called once per file; it receives the
latest event once per worker run. Apps that need all processed events can use the
batch callback or batch flow.

## What Koog Produces

When `useAgent=true` and an LLM key is available, `CrashAnalysisAgent` runs Koog
and builds a richer `ProcessedCrashReport`:

- title
- description
- severity
- confidence
- steps to reproduce
- possible cause
- parsed crash file
- parsed crash line
- parsed crash method
- app-code classification
- sanitized message/stack/breadcrumbs
- fingerprint

The raw crash is still included in the event as `event.raw`, including device
and app metadata.

## Raw Fallback Behavior

Raw fallback is used when:

- `useAgent=false`
- no LLM key is configured
- Koog/LLM throws or the key is invalid
- app wants callback/ticket payloads without agentic content

Fallback still creates a `ProcessedCrashReport`, but it is derived locally from
the raw crash via `CrashReportBuilder`.

Fallback events are marked:

```text
agentic = false
analysisSource = RAW_FALLBACK
```

If Koog attempted analysis and failed, the SDKOnly orchestrator can also include:

```text
analysisError = <error message>
```

Fallback still includes:

- raw exception type
- raw message
- raw stack trace
- device info
- app version
- build type/environment
- screen
- breadcrumbs
- custom metadata
- fingerprint
- local grouping data

If Notion/GitHub sinks are selected, fallback sends the raw-derived event to
those sinks. It does not invent agentic content.

## Local Aggregation

SDKOnly aggregation groups crashes locally by:

```text
appId + environment + fingerprint
```

The current event model separates:

- `LocalBugGroup`: grouped incident
- `LocalCrashOccurrence`: raw occurrence metadata
- `MBAAgentEvent`: the processed event passed to callbacks/sinks

A repeated crash should produce a new occurrence and update the existing group.
Optional external sinks should not create a second parent artifact for the same
group when the existing URL/state is already stored.

## External Sinks

`mba-agent` only knows the generic `MBAAgentSink` contract:

```kotlin
public interface MBAAgentSink {
    public val name: String
    public suspend fun sync(event: MBAAgentEvent): MBAAgentSinkResult
}
```

`mba-notion` and `mba-github` can be plugged in by the app, but the agent module
does not import them directly.

Expected sink behavior:

- New group: create parent ticket/issue if selected.
- Duplicate group: update existing parent or create only an occurrence row if
  that sink supports it.
- GitHub issue creation should be idempotent for a group.
- Notion parent row creation should be idempotent for a group.

## Android App-Facing Callbacks

`MBAAndroid.saveConfig(...)` supports object callbacks and JSON callbacks.

Latest-only callbacks:

```kotlin
callback = MBAAgentCallback { event ->
    // Latest processed crash event from this worker run.
}

jsonCallback = { json ->
    // Same latest event serialized by the SDK.
}
```

Batch callbacks:

```kotlin
batchCallback = MBAAgentBatchCallback { batch ->
    // batch.latest is the same event delivered to callback.
    // batch.events contains all processed pending events.
}

batchJsonCallback = { json ->
    // Full batch JSON serialized by the SDK.
}
```

Flows are also available:

```kotlin
MBAAndroid.agentEvents
MBAAndroid.agentEventJson
MBAAndroid.agentEventBatches
MBAAndroid.agentBatchEventJson
```

Architectural rule:

- The default callback is latest-only to keep app behavior simple.
- The batch callback/flow exists so no processed crash is hidden from apps that
  want full control.
- Internal processing still handles all pending crash files.

## Sample App Runtime Controls

The sample app uses `SampleRuntime` to keep processing mode dynamic at the app
layer.

Build values still exist as defaults:

```properties
MBA_SAMPLE_MODE=sdkOnly
MBA_SAMPLE_USE_AGENT=true
```

At runtime, the sample UI can switch:

- SDKOnly vs hosted backend
- Koog agent on/off
- optional integrations:
  - callback only
  - Notion
  - GitHub
  - both

The runtime selection is persisted in sample app `SharedPreferences` and applied
through `MBAAndroid.saveConfig(...)`.

## Key Runtime Flags

### `sendToBackend`

Owned by `mba-android` config.

- `true`: try backend upload first.
- `false`: process locally through SDKOnly/fallback path.

### `useAgent`

Owned by SDK/app config.

- `true`: create local Koog/LLM analyzer when an LLM key exists.
- `false`: skip Koog and use raw fallback.

SDKOnly with a blank LLM key is valid only when `useAgent=false`.

### `skipNotion`

Stored on raw crash for hosted/backend flows and used by external integrations
where relevant.

### `skipGitIssue`

SDKOnly sink routing flag used by `SdkOnlyCrashOrchestrator` and
`LocalFallbackCrashOrchestrator`. In Android wiring, GitHub is skipped when no
GitHub sink is registered.

## Event Field Guide

`MBAAgentEvent` is the owner-facing event payload.

Important fields:

- `mode`: `SDK_ONLY` or `SDK_ONLY_FALLBACK`
- `group`: grouped bug state
- `occurrence`: current raw occurrence metadata
- `report`: analyzed or fallback processed report
- `raw`: original raw crash snapshot
- `externalState`: known Notion/GitHub URLs and IDs
- `isNewGroup`: whether this occurrence created the group
- `agentic`: true only when Koog produced the report
- `analysisSource`: `KOOG`, `RAW_FALLBACK`, or `LOCAL_DUPLICATE`
- `analysisError`: set when analysis failed and fallback was used

`MBAAgentBatchEvent` wraps:

- `latest`: latest event by raw crash timestamp
- `events`: all processed events from that worker run
- `totalCount`
- `successCount`
- `failCount`

## What `mba-agent` Should Not Own

Keep these out of `mba-agent`:

- Android APIs
- WorkManager scheduling
- Notion SDK/client imports
- GitHub SDK/client imports
- server-only APIs
- UI/sample runtime preferences
- direct file paths for Android app storage

Use interfaces and adapters instead.

## Current Owner Checklist

When changing SDKOnly behavior, verify:

- `:mba-agent:jvmTest`
- `:mba-android:testDebugUnitTest`
- `:mba-sample:assembleDebug`

Also manually check Logcat labels:

```text
MBA/Sample: SDKOnly latest callback...
MBA/Sample: App-layer latest callback JSON[0]: ...
MBA/Sample: SDKOnly batch callback...
MBA/Sample: App-layer batch callback JSON[0]: ...
MBA/MBAAndroid: SDKOnly callback batch JSON[0]: ...
```

If only backend mode is enabled, local SDKOnly callbacks may not appear because
the backend accepted the crash and local processing was skipped.
