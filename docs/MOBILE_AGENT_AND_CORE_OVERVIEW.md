# Mobile Agent and Core Architecture

Mobile Bug Agent is a crash and error intelligence SDK for mobile apps. It
captures technical failure context, stores it safely inside the app, processes
it after the app is running again, groups duplicates, and can turn the result
into a developer-ready bug report.

The SDK is designed so teams can choose either:

- SDKOnly mode, where processing runs locally in the app
- hosted mode, where raw crashes are sent to the MBA backend

## Plain-English Flow

```text
1. The app installs Mobile Bug Agent.

2. The SDK waits for failure signals. It does not record the screen or track
   user behavior.

3. If the app crashes, ANRs on a supported Android version, or explicitly logs
   a non-fatal error, MBA writes a technical crash snapshot to app-private
   storage.

4. When the app starts again, a background worker reads pending crash snapshots.

5. The crash is processed locally or sent to the hosted backend, depending on
   the app's configuration.

6. Repeated crashes are grouped so the same bug does not create duplicate
   parent tickets.

7. The app receives callbacks or JSON payloads. Optional Notion and GitHub
   modules can create or update external records.
```

## Module Responsibilities

### `mba-core`

`mba-core` contains shared crash data models and capture primitives. It defines
the common objects used across Android, agent, server, and future KMP targets.

It is responsible for:

- `RawCrashReport`
- `ProcessedCrashReport`
- `DeviceContext`
- `CrashFingerprint`
- `CrashReportBuilder`
- `PIISanitizer`
- platform crash writing through `CrashWriter`
- basic crash configuration models

It does not run Koog and does not create Notion or GitHub tickets.

### `mba-agent`

`mba-agent` contains local SDKOnly processing. It receives a raw crash report
and returns a processed event.

It is responsible for:

- Koog/LLM crash analysis
- raw fallback when analysis is disabled or fails
- local duplicate grouping
- occurrence tracking
- app-facing event models
- batch event models
- generic sink contracts for optional delivery

It does not depend directly on Android, WorkManager, Notion, or GitHub.

### `mba-android`

`mba-android` is the Android runtime adapter.

It is responsible for:

- Android installation through `MBAAndroid.install(...)`
- saving runtime configuration
- installing the crash handler through `mba-core`
- capturing Android app/device metadata
- detecting previous ANR exits on Android 11/API 30+
- scheduling `CrashUploadWorker`
- reading pending crash files
- choosing hosted upload or SDKOnly processing
- publishing callbacks, JSON callbacks, and flows

It does not contain Notion or GitHub clients.

### `mba-notion` and `mba-github`

These are optional integration modules. Apps add them only when they want MBA to
create or update external records.

They are responsible for:

- mapping processed crash events into Notion or GitHub payloads
- creating a parent record for a new bug group
- updating an existing record for duplicate crashes
- returning external URLs or delivery errors to the SDK event

## Android Lifecycle

The Android SDK is installed at app startup, usually from `Application.onCreate`
or AndroidX Startup.

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        MBAAndroid.install(this)
    }
}
```

At install time, MBA:

- prepares app-private crash storage
- installs the fatal crash handler
- captures app/device metadata
- checks for a previous ANR exit when supported
- schedules a WorkManager job to process pending crash files

Fatal crashes are caught through the process/thread uncaught exception handler,
not Activity lifecycle callbacks. The app can add better context by calling:

```kotlin
MBA.setScreen("CheckoutScreen")
MBA.addBreadcrumb("Tapped Pay")
MBA.logError(error, metadata = mapOf("flow" to "checkout"))
```

## Fatal Crash Handling

When an uncaught exception happens:

```text
1. Android invokes the uncaught exception handler chain.
2. MBA receives the throwable.
3. MBA writes RawCrashReport JSON to app-private storage.
4. MBA delegates to the previous/default handler.
5. Android continues normal crash behavior and terminates the process.
```

MBA intentionally keeps this path small. It does not run LLM analysis or create
tickets while the process is crashing.

## App Restart Processing

When the app opens again:

```text
1. MBA installs again during app startup.
2. WorkManager starts CrashUploadWorker.
3. Pending crash JSON files are read.
4. Each crash is processed by hosted mode or SDKOnly mode.
5. Local grouping updates the matching bug group.
6. Optional Notion/GitHub sinks run if configured.
7. Successfully processed crash files are deleted.
8. Callbacks, JSON callbacks, and flows receive the result.
```

If several crashes are pending, the worker processes all of them. Apps can use
batch callbacks or flows to receive the full list.

## ANR Handling

On Android 11/API 30 and newer, Android exposes historical process exit reasons
through `ApplicationExitInfo`. MBA uses this to detect whether the previous app
process ended because of an ANR.

ANR handling works after restart:

```text
1. The app becomes unresponsive and Android terminates the process for ANR.
2. The user opens the app again.
3. MBA checks historical process exits.
4. If a new ANR exit is found, MBA creates a RawCrashReport for it.
5. The normal worker pipeline processes that report.
```

This is not the same as live ANR tracing. MBA currently records supported ANR
exits after Android reports them on the next app start.

## SDKOnly and Hosted Modes

### SDKOnly

SDKOnly mode keeps processing in the app. It can run local Koog/LLM analysis,
fall back to raw reports, group duplicates locally, emit callbacks/JSON, and
optionally deliver to app-owned Notion/GitHub integrations.

### Hosted

Hosted mode sends raw crash reports to the configured MBA backend. The backend
can run analysis, grouping, and external ticket sync server-side.

An app can expose this choice as a runtime setting, as the sample app does, or
configure it statically for a build.

## What The SDK Monitors

MBA monitors failure diagnostics:

- fatal JVM/Kotlin exceptions
- explicit non-fatal errors logged by the app
- coroutine exceptions routed through the MBA helper
- supported ANR process exits after app restart
- app/device/build metadata needed to debug the failure
- screen names, breadcrumbs, and custom metadata only when the app provides them

MBA is not a product analytics SDK. It does not intentionally collect
screenshots, screen recordings, taps, full navigation history, contacts,
location, app databases, media, cookies, auth tokens, or network payloads.

For the full privacy boundary, see `MONITORING_SECURITY_CLAIMS.md`.

## Current Limitations

- ANR detection requires Android 11/API 30+ historical process exit reasons.
- Native crash capture is not included yet.
- Redaction is a guardrail, not a guarantee that every sensitive value has been
  removed.
- Crash reports can include sensitive values if the app puts them in exception
  messages, breadcrumbs, screen names, stack traces, ANR traces, or custom
  metadata.
