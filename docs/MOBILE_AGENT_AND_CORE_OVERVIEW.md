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

3. If the app crashes, ANRs, or explicitly reports a non-fatal error, the SDK
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

## Common Lifecycle Questions

### How does it bind to the Android lifecycle?

The Android SDK binds at app startup, not to individual Activities.

There are two paths:

- AndroidX Startup can call `MBAAndroid.install(context)` automatically through
  `MBAInitializer`.
- The app can also call `MBAAndroid.install(this)` manually from
  `Application.onCreate`.

`MBAAndroid.install(context)` does three important things:

- injects Android app metadata into `mba-core`
- calls `MBA.install(crashDir)` so `mba-core` installs the platform crash
  handler
- enqueues a unique `CrashUploadWorker` through WorkManager to process pending
  crash files from previous runs

The SDK does not need Activity lifecycle callbacks to catch crashes. Fatal
crashes are caught at the process/thread exception-handler level.

The app can still give better context by calling:

```kotlin
MBA.setScreen("CheckoutScreen")
MBA.addBreadcrumb("Tapped Pay")
MBA.logError(error, metadata = mapOf("flow" to "checkout"))
```

### What happens when the app crashes and Android kills it?

Fatal crash sequence:

```text
1. App throws an uncaught exception.

2. Android invokes the default uncaught exception handler chain.

3. MBA's handler receives the throwable first.

4. MBA writes a RawCrashReport JSON file synchronously to app-private storage.

5. MBA calls the previous/default handler.

6. Android continues normal fatal-crash behavior and kills the app process.
```

The SDK does not try to upload or run AI analysis during the fatal crash handler.
That would be unreliable because the process is already dying. The safe work in
that moment is only to write the raw crash snapshot to disk.

### What happens when the user reopens the app?

Reopen sequence:

```text
1. Application starts again.

2. MBAAndroid.install(context) runs again.

3. WorkManager starts CrashUploadWorker when constraints allow it.

4. PendingCrashProcessor reads every saved mba_crash_*.json file.

5. The worker processes each raw crash:
   - hosted mode: upload to backend
   - SDKOnly + useAgent: run local Koog/LLM analysis
   - SDKOnly fallback: build raw-derived report

6. Local aggregation groups duplicates by app/environment/fingerprint.

7. Optional Notion/GitHub sinks run if registered.

8. Successfully processed files are deleted.

9. App receives latest callback/JSON and, if configured, batch callback/JSON.
```

This is why the crash often appears in callbacks or tickets after the next app
launch, not during the crash itself.

### What happens in release builds when there are no logs?

Crash capture does not depend on Logcat.

`MBALog` is gated by the SDK `debug` flag. In release builds, apps usually keep
`debug=false`, which means SDK logs are silent. The actual crash capture still
works because it writes JSON crash files directly to app-private storage.

In release:

- fatal crash capture still works
- non-fatal `MBA.logError(...)` still works
- pending crash processing still works
- hosted upload still works if configured
- SDKOnly callbacks still work if configured
- Logcat debug output is normally off

For development/demo builds, set `debug=true` to see:

```text
MBA/MBAAndroid
MBA/UploadWorker
MBA/CrashPipeline
MBA/Sample
```

For production, rely on the configured backend, callbacks, or external sinks
instead of Logcat.

### How does it catch ANRs?

Current status: ANR capture is implemented for Android 11/API 30+ using
`ApplicationExitInfo`.

ANRs are different from normal exceptions. Android reports ANRs when the main
thread is blocked and the system decides the app is not responding. They do not
arrive through `Thread.UncaughtExceptionHandler`, so the fatal crash handler
cannot catch them at the moment they happen.

Instead, the Android adapter checks the previous process exit history when the
app starts again:

```text
Previous run ends with ANR
  |
  v
Android records ApplicationExitInfo.REASON_ANR
  |
  v
User opens app again
  |
  v
MBAAndroid.install(context)
  |
  v
ANRExitReporter checks historical process exits
  |
  v
If a new ANR is found, write RawCrashReport(type = REASON_ANR)
  |
  v
CrashUploadWorker processes it like any other pending crash
```

The generated ANR report includes:

- exception type `android.app.ApplicationExitInfo.REASON_ANR`
- ANR description when Android provides one
- ANR trace text when Android provides a trace input stream
- process name/status/importance metadata
- app version/build type
- OS/device metadata

Limitations:

- API 29 and below do not support this `ApplicationExitInfo` path.
- This is restart-time detection, not live in-process ANR detection.
- A main-thread watchdog is still a possible future enhancement for detecting
  long stalls while the app process is still alive.

Current SDK catches these failure classes:

- uncaught JVM/Kotlin exceptions
- explicit `MBA.logError(...)` non-fatal errors
- coroutine exceptions routed through `MBA.exceptionHandler`
- ANR process exits on Android 11/API 30+ after app restart

### What about native crashes?

Current status: native crash capture is not implemented.

The current Android crash handler catches JVM/Kotlin exceptions. Native crashes
from C/C++/JNI generally require a native signal handler or platform crash
report integration. That should be a separate feature and should not be claimed
as supported yet.

### What about app force-stop, device reboot, or worker constraints?

Crash files remain in app-private storage until processed successfully.

If the app crashes, is killed, loses network, or WorkManager is delayed, the raw
crash file stays on disk. The next successful worker run can process it.

Limitations:

- If the user clears app data, pending crash files are deleted.
- If the app is force-stopped, Android may not run WorkManager until the user
  opens the app again.
- If hosted upload is configured but network is unavailable, WorkManager can
  retry later.

## What The SDK Monitors

The SDK monitors failures, not normal user activity.

It captures:

- fatal uncaught exceptions
- ANR process exits on Android 11/API 30+ after app restart
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

### ANRs

On Android 11/API 30+, `mba-android` checks `ApplicationExitInfo` on app start.
If Android says the previous process exited because of `REASON_ANR`, MBA writes
an ANR `RawCrashReport` and lets the normal worker/agent pipeline process it.

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
- ANR capture currently uses Android 11/API 30+ `ApplicationExitInfo`; API 29
  and below need a future watchdog or other strategy.
- Native crash capture is not implemented yet.

These are acceptable for the current architecture if they are explained clearly
and the app integration guidance is strict about not passing sensitive data.
