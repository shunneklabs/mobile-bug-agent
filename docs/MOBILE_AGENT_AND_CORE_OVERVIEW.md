# Mobile Agent and Core Overview

This document explains, in product and architecture terms, what the mobile agent
does inside an app, how it catches bugs, what it tracks, and what security or
privacy boundaries exist.

Use this when explaining Mobile Bug Agent to other engineers, app owners,
security reviewers, or integration partners.

## Audience Map

Read this document in two levels:

- Non-dev / product / security readers: read `Short Explanation`,
  `Plain-English Flow`, `What The SDK Monitors`, `What The SDK Does Not
  Monitor`, and `Security and Privacy Boundary`.
- Mobile/backend developers: also read `Developer Flow`, module
  responsibilities, callback shape, and current limitations.

## Short Explanation

Mobile Bug Agent is a crash and error intelligence SDK.

In the app, the SDK installs a crash handler and gives developers a small API to
add context such as the current screen and breadcrumbs. When a crash or logged
error happens, the SDK writes a structured crash snapshot locally. Later, a
background worker processes that snapshot: it can run local agent analysis,
group duplicates, emit callbacks/JSON to the app, and optionally send the
result to app-owned Notion/GitHub integrations or to the hosted backend.

The agent is not a general surveillance layer. It does not record the screen,
track taps automatically, read app databases, inspect network traffic, collect
contacts, collect location, or monitor user behavior outside the explicit crash
context.

## Plain-English Flow

This is the simple version to explain to anyone:

```text
1. The app installs the Mobile Bug Agent SDK.

2. The SDK waits quietly. It does not record the user or watch the screen.

3. If the app crashes, or the app explicitly reports a non-fatal error, the SDK
   saves a technical crash note inside the app's private storage.

4. When the app opens again, a background worker reads those saved crash notes.

5. The SDK groups repeated crashes so the same bug does not create duplicate
   tickets.

6. If SDKOnly agent mode is enabled, the local agent can analyze the crash and
   produce a clearer bug report: title, severity, likely cause, and steps to
   reproduce.

7. If the local agent is disabled or fails, the SDK still produces a raw
   technical report so the developer does not lose the crash.

8. The app receives the result through callbacks or JSON. If the app enabled
   Notion or GitHub, the SDK can also create or update those external records.
```

In one sentence:

> The mobile agent turns crashes into grouped, explainable bug reports, while
> keeping the app in control of where the data goes.

## One-Minute Architecture

```text
App code
  |
  | install SDK, configure mode, optionally set screen/breadcrumbs
  v
mba-core
  |
  | catches fatal crashes and explicit non-fatal errors
  | writes RawCrashReport JSON to app-private storage
  v
mba-android
  |
  | WorkManager reads pending crash files
  | applies runtime config
  | sends raw report to backend OR runs local SDKOnly pipeline
  v
mba-agent
  |
  | Koog/LLM analysis or raw fallback
  | local grouping and occurrence tracking
  | app callback/event JSON
  | optional generic sinks
  v
optional modules
  |
  | mba-notion, mba-github, or app-owned callback handling
```

## Developer Flow

This is the lower-level runtime sequence.

```text
Application.onCreate
  |
  | MBAAndroid.saveConfig(...)
  | - backend endpoint / SDKOnly mode
  | - LLM config
  | - useAgent true/false
  | - callbacks
  | - optional sinks
  |
  | MBAAndroid.install(context)
  v
MBA.install(crashDir)
  |
  | installs platform uncaught-exception handler
  | initializes breadcrumb buffer
  | stores crash dir path
  v
Normal app runtime
  |
  | app may call:
  | - MBA.setScreen("Checkout")
  | - MBA.addBreadcrumb("Tapped Pay")
  | - MBA.logError(...)
  v
Crash or logged error
  |
  | MBA.handleCrash(...)
  | CrashWriter.writeToDisk(...)
  v
RawCrashReport JSON in app-private files
  |
  | next app run / WorkManager
  v
CrashUploadWorker
  |
  | PendingCrashProcessor.readPending(...)
  | for each raw crash:
  |   CrashDeliveryPipeline.process(raw)
  v
Delivery decision
  |
  +-- hosted backend enabled:
  |     ServerReportUploader -> /report
  |
  +-- SDKOnly + useAgent:
  |     CrashAnalysisAgent -> Koog/LLM -> SdkOnlyCrashOrchestrator
  |
  +-- SDKOnly fallback:
  |     CrashReportBuilder -> LocalFallbackCrashOrchestrator
  v
Local aggregation
  |
  | LocalCrashAggregationStore.upsert(...)
  | creates/updates BugGroup and CrashOccurrence
  v
Optional external sinks
  |
  | Notion/GitHub if app registered them
  v
Android callbacks and flows
  |
  | latest callback / latest JSON
  | batch callback / batch JSON
```

## What The SDK Monitors

The SDK monitors failures, not normal user activity.

It captures:

- fatal uncaught exceptions
- explicit non-fatal errors logged by the app through `MBA.logError(...)`
- coroutine errors when the app uses `MBA.exceptionHandler`
- the current screen name, only if the app calls `MBA.setScreen(...)`
- breadcrumbs, only if the app calls `MBA.addBreadcrumb(...)`
- app-level metadata injected by the Android adapter
- developer-provided metadata passed with a logged error

## What The SDK Does Not Monitor

The SDK does not automatically capture:

- screenshots
- screen recordings
- UI text from the screen
- tap coordinates
- full navigation history
- analytics events
- network requests or responses
- request headers
- cookies
- app database contents
- files from app storage beyond the SDK crash files
- contacts
- photos
- microphone, camera, Bluetooth, or sensor data
- GPS/location
- device identifiers such as IMEI, Android ID, advertising ID, phone number, or
  account email

If an app developer puts sensitive values into exception messages,
breadcrumbs, screen names, or custom metadata, those values can become part of
the crash report. That is an integration responsibility and should be covered in
SDK usage guidance.

## How Bugs Are Caught

### Fatal crashes

`mba-core` installs a platform crash handler.

On Android, it wraps the default `Thread.UncaughtExceptionHandler`:

```text
Thread crashes
  -> MBA handler receives throwable
  -> MBA writes crash report to disk
  -> previous/default handler is called so normal crash behavior continues
```

The SDK does not swallow fatal crashes. It records the crash, then lets the app
crash normally.

### Non-fatal errors

Apps can explicitly call:

```kotlin
MBA.logError(throwable, metadata)
```

That creates the same type of raw crash snapshot, but marks it as non-fatal.

### Coroutine errors

`MBA.exceptionHandler` can be attached to coroutine scopes. When a coroutine
throws into that handler, MBA records it as a non-fatal error with coroutine
context.

## What `mba-core` Does

`mba-core` is the shared foundation.

It owns:

- SDK install/configure state
- platform crash handler abstraction
- current screen context
- breadcrumb buffer
- raw crash model
- processed crash model
- device model
- severity model
- crash fingerprinting utilities
- PII sanitizer
- raw crash file writing
- fallback report builder

On Android, `CrashWriter` writes one JSON file per crash into app-private
storage:

```text
<app files dir>/mba-crashes/mba_crash_<id>_<timestamp>.json
```

The raw report includes:

- random crash id
- timestamp
- exception type
- exception message
- stack trace
- thread name
- fatal/non-fatal flag
- device context
- app version
- build type
- current screen, if set
- breadcrumbs, if added
- custom metadata, if provided
- routing flags such as `autoFix` and `skipNotion`

`mba-core` does not:

- run Koog
- call Gemini/OpenAI/other LLM providers
- upload to backend
- create Notion tickets
- create GitHub issues
- schedule Android workers
- own Android UI or sample app state

## What `mba-android` Does

`mba-android` is the Android adapter around `mba-core` and `mba-agent`.

It owns:

- `MBAAndroid.install(context)`
- Android app metadata collection
- crash directory location
- WorkManager scheduling
- reading pending crash files
- runtime processing config in `SharedPreferences`
- optional backend upload
- optional SDKOnly local processing
- Android file-backed aggregation store
- app callbacks and flows

Android app metadata currently includes:

- package name / app id
- version name
- version code
- debug/release build type
- Android SDK version
- Android OS version
- device model
- device manufacturer

This is diagnostic metadata. It is not a stable user identity.

## What `mba-agent` Does

`mba-agent` is the SDKOnly processing brain.

It owns:

- local Koog/LLM crash analysis
- raw fallback processing when Koog is disabled or fails
- local grouping and occurrence contracts
- SDKOnly event models
- optional sink contract
- pipeline decision ordering

When agent analysis succeeds, it generates:

- title
- description
- severity
- confidence
- steps to reproduce
- possible cause
- parsed crash location
- app-code classification
- sanitized stack/message/breadcrumbs
- fingerprint

When agent analysis is disabled or fails, it still returns a raw-derived
fallback report and marks the event:

```text
agentic = false
analysisSource = RAW_FALLBACK
```

The app still receives the raw crash details and device/app metadata in the
callback.

## What The Mobile Agent Tracks

The mobile agent tracks only crash-relevant context:

- crash occurrence id
- grouped bug id
- fingerprint
- occurrence count
- unique device count
- first seen / last seen
- app id
- environment/build type
- app version
- OS version
- device model/manufacturer
- screen name, if app provided it
- breadcrumbs, if app provided them
- external ticket URLs/IDs, if Notion/GitHub are enabled

It does not track sessions as a user analytics product. It does not build user
profiles. It does not need a login identity to group crashes.

For demo/local grouping, device identity should be treated as an anonymous
diagnostic hash or derived device key, not a real user id.

## What Is Sent Out Of The App

This depends on mode and app settings.

### Hosted / SaaS mode

The app sends raw crash reports to the configured backend endpoint. The backend
then owns analysis, aggregation, Notion, and GitHub.

### SDKOnly mode with agent enabled

The app processes the crash locally. If the app registers Notion/GitHub sinks,
the app sends analyzed/fallback ticket payloads directly to those providers
using app-owned keys.

### SDKOnly mode with agent disabled

The app skips Koog/LLM analysis. It still groups the crash locally and emits raw
fallback callbacks/JSON. If Notion/GitHub sinks are enabled, they receive the
raw-derived fallback payload.

### Callback-only mode

The SDK does not upload to Notion/GitHub/backend. The app receives callback
objects or JSON and decides what to do.

## Callback Shape

The Android adapter exposes both object and JSON callbacks.

Latest-only callback:

```kotlin
callback = MBAAgentCallback { event ->
    // Latest processed crash from this worker run.
}
```

Latest-only JSON:

```kotlin
jsonCallback = { json ->
    // SDK-serialized latest event JSON.
}
```

Full batch callback:

```kotlin
batchCallback = MBAAgentBatchCallback { batch ->
    // batch.latest is what callback receives.
    // batch.events contains all processed pending crashes.
}
```

Full batch JSON:

```kotlin
batchJsonCallback = { json ->
    // SDK-serialized batch JSON.
}
```

Default app callback behavior is latest-only to avoid callback spam when the app
has several pending crash files. The full batch path exists for apps that want
complete control.

## Security and Privacy Boundary

The SDK intentionally keeps its monitoring surface narrow.

### Data collected by default

Default crash payload data:

- exception type
- exception message
- stack trace
- thread name
- fatal/non-fatal
- timestamp
- app version/build type
- package/app id
- OS/device model metadata
- current screen only if app sets it
- breadcrumbs only if app adds them
- custom metadata only if app passes it

### Sensitive data risk

The main risk is not hidden device monitoring. The main risk is app-provided or
exception-provided content:

- exception messages can contain user input
- stack traces can contain file paths or URLs
- breadcrumbs can contain app-specific values
- custom metadata can contain anything the developer passes
- screen names can accidentally include ids if the app names screens that way

Therefore, SDK docs should tell integrators:

- do not put emails, phone numbers, auth tokens, access tokens, payment data, or
  raw user input in breadcrumbs
- do not use screen names like `Checkout/user=jane@example.com`
- do not pass sensitive custom metadata
- prefer stable internal labels like `CheckoutScreen` or `PaymentConfirmation`

### PII sanitizer

`mba-core` includes a regex-based `PIISanitizer` that can redact common patterns:

- email addresses
- phone-like numbers
- payment-card-like numbers
- bearer tokens
- IPv4 addresses
- IPv6 addresses
- custom regex patterns supplied by the app

This is a guardrail, not a legal/privacy guarantee. The strongest protection is
still to avoid putting sensitive values into crash context in the first place.

### Local storage

Crash files are written to app-private storage. Other normal apps cannot read
that directory on Android. The files remain until the worker processes them
successfully.

### Network and keys

In SDKOnly mode:

- LLM keys are app-owned.
- Notion/GitHub keys are app-owned if those integrations are enabled.
- The SDK should not log API keys.
- The app decides whether to upload anywhere.

In hosted mode:

- the app sends crash payloads to the configured backend
- the backend owns provider keys and external ticket creation

## How To Explain It To Others

Use this framing:

> The mobile SDK is a crash-context agent, not a user-monitoring agent. It
> listens for crashes and explicitly logged errors, captures technical debugging
> context, and turns that into a grouped bug report. The app controls what extra
> context is attached, where the output goes, and whether local AI analysis is
> enabled.

For security reviewers:

> By default it collects exception data, stack traces, device/app diagnostic
> metadata, and app-provided breadcrumbs/screen names. It does not collect
> screenshots, contacts, location, network payloads, cookies, or user identities.
> Sensitive data can appear only if the app or exception messages put it into
> crash context, so integrators should keep breadcrumbs and metadata sanitized.

For mobile engineers:

> Install early in `Application.onCreate`, configure the processing mode, and
> use `setScreen`, `addBreadcrumb`, and `logError` intentionally. Treat
> breadcrumbs like logs: useful for debugging, but never a place for secrets or
> raw personal data.

## Ownership Summary

```text
mba-core
  capture primitives, raw crash file, shared models, sanitizer, fingerprint

mba-android
  Android install, metadata, WorkManager, config persistence, callbacks/flows

mba-agent
  SDKOnly Koog analysis, raw fallback, grouping contracts, event contracts

mba-notion / mba-github
  optional external ticket delivery, registered by the app

mba-sample
  demo UI and runtime toggles only
```

## Current Limitations To Be Clear About

- Regex redaction is best effort.
- Stack traces and exception messages can still carry app-specific sensitive
  data if the app puts it there.
- The SDK does not yet enforce a strict allowlist for custom metadata.
- Local aggregation is file-backed for the current SDKOnly/demo phase.
- If hosted backend mode accepts a crash, local SDKOnly callback analysis may
  not run for that crash.

These are acceptable for the current architecture if they are explained clearly
and the app integration guidance is strict about not passing sensitive data.
