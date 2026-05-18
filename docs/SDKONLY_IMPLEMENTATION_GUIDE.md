# SDKOnly Android Implementation Guide

This guide shows how to add Mobile Bug Agent to an Android app in SDKOnly mode.
SDKOnly mode processes crashes inside the app and lets the app decide whether
to keep results in callbacks, send them to Notion, send them to GitHub, or send
them somewhere else.

## What SDKOnly Does

SDKOnly mode can:

- capture fatal JVM/Kotlin crashes
- record explicit non-fatal errors
- detect supported Android ANR exits after app restart
- write raw crash reports to app-private storage
- process pending reports after the app starts again
- run Koog/LLM analysis when configured
- fall back to raw technical reports when analysis is disabled or fails
- group duplicate crashes locally
- emit Kotlin callbacks, flows, and JSON payloads
- optionally deliver grouped bugs to Notion and GitHub

## 1. Add Dependencies

For a published SDK, the app should depend on the Android adapter:

```kotlin
dependencies {
    implementation("dev.sunnat629.mba:mba-android:<version>")
}
```

If you are running from this repository, use the project dependency:

```kotlin
dependencies {
    implementation(project(":mba-android"))
}
```

Add optional integrations only when the app needs them:

```kotlin
dependencies {
    implementation("dev.sunnat629.mba:mba-notion:<version>")
    implementation("dev.sunnat629.mba:mba-github:<version>")
}
```

Repository builds use:

```kotlin
dependencies {
    implementation(project(":mba-notion"))
    implementation(project(":mba-github"))
}
```

Apps do not need to add `mba-core` or `mba-agent` separately when using the
published Android SDK. The Android adapter brings the required core and agent
pieces.

## 2. Store Secrets Outside Source Control

Do not hard-code provider keys in source files. Use your normal secret
management approach, build config, encrypted remote config, or CI-injected
values.

For local development, `local.properties` is enough:

```properties
GEMINI_API_KEY=your_gemini_key
NOTION_API_KEY=your_notion_token
NOTION_TICKET_DB_ID_OR_URL=your_notion_database_id
NOTION_CRASH_DB_ID_OR_URL=optional_crash_occurrence_database_id
GITHUB_TOKEN=github_pat_or_app_token
GITHUB_OWNER=owner
GITHUB_REPO=repo
```

## 3. Initialize Early In `Application`

Configure MBA as early as possible in `Application.onCreate`.

Common imports used by the snippets:

```kotlin
import android.app.Application
import android.util.Log
import dev.sunnat629.mba.android.MBAAndroid
import dev.sunnat629.mba.core.MBA
import dev.sunnat629.mba.core.config.LLM
import dev.sunnat629.mba.core.config.MBAConfig
import dev.sunnat629.mba.core.config.MBAMode
```

```kotlin
class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        MBAAndroid.install(this)

        MBA.configure(
            MBAConfig.Builder().apply {
                mode = MBAMode.SdkOnly(llmApiKey = BuildConfig.GEMINI_API_KEY)
                useAgent = BuildConfig.GEMINI_API_KEY.isNotBlank()
                debug = BuildConfig.DEBUG
            }.build(),
        )

        MBAAndroid.saveConfig(
            context = this,
            sendToBackend = false,
            llm = if (BuildConfig.GEMINI_API_KEY.isBlank()) {
                null
            } else {
                LLM.gemini(BuildConfig.GEMINI_API_KEY)
            },
            useAgent = BuildConfig.GEMINI_API_KEY.isNotBlank(),
            callback = { event ->
                Log.i("MBA", "Crash group=${event.group.id}, title=${event.report.title}")
            },
            batchCallback = { batch ->
                Log.i("MBA", "Processed ${batch.totalCount} pending crash report(s)")
            },
            jsonCallback = { json ->
                Log.d("MBA", "Latest SDKOnly event JSON: $json")
            },
            batchJsonCallback = { json ->
                Log.d("MBA", "SDKOnly batch JSON: $json")
            },
            debug = BuildConfig.DEBUG,
        )
    }
}
```

The Android artifact also includes AndroidX Startup metadata, so install may
already have run before `Application.onCreate`. Calling `MBAAndroid.install(this)`
again is safe; it is idempotent. Keeping the explicit call makes the setup work
even if an app disables AndroidX Startup.

## 4. Choose Agent Or Raw Fallback

Use local agent analysis when the app has an LLM key and wants richer reports:

```kotlin
mode = MBAMode.SdkOnly(llmApiKey = BuildConfig.GEMINI_API_KEY)
useAgent = true
```

Use raw fallback when the app does not want local LLM analysis:

```kotlin
mode = MBAMode.SdkOnly(llmApiKey = "")
useAgent = false
```

Raw fallback still produces callback and JSON payloads with exception details,
device/app metadata, fingerprint, grouping information, and optional external
delivery. It does not generate agentic fields such as inferred root cause or
richer reproduction steps.

## 5. Add Runtime Context

MBA captures technical crash data automatically. Add only safe, intentional
context.

```kotlin
MBA.setScreen("CheckoutScreen")
MBA.addBreadcrumb("Tapped Pay")
```

For non-fatal errors:

```kotlin
try {
    submitOrder()
} catch (error: Throwable) {
    MBA.logError(
        throwable = error,
        metadata = mapOf("flow" to "checkout"),
    )
}
```

For coroutine scopes:

```kotlin
val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + MBA.exceptionHandler)
```

Do not put emails, tokens, payment data, raw user input, or private content in
screen names, breadcrumbs, exception messages, or metadata.

## 6. Use Callback-Only Mode

Callback-only mode is the smallest SDKOnly setup. The SDK processes crashes and
returns results to the app. The app owns the next step.

```kotlin
MBAAndroid.saveConfig(
    context = this,
    sendToBackend = false,
    llm = LLM.gemini(BuildConfig.GEMINI_API_KEY),
    useAgent = true,
    callback = { event ->
        // Latest processed event from this worker run.
    },
    batchCallback = { batch ->
        // All events processed in this worker run.
    },
    jsonCallback = { json ->
        // Latest event as JSON.
    },
    batchJsonCallback = { json ->
        // Full batch as JSON.
    },
)
```

Use latest callbacks for simple UI or logging. Use batch callbacks when the app
needs every pending crash processed during that worker run.

## 7. Enable Notion Or GitHub

Add only the integration modules the app needs.

```kotlin
val notionBackend = NotionTicketBackend(
    apiKey = BuildConfig.NOTION_API_KEY,
    bugTicketDbId = BuildConfig.NOTION_TICKET_DB_ID,
    crashReportDbId = BuildConfig.NOTION_CRASH_DB_ID.ifBlank { null },
)

val githubBackend = GitHubIssueBackend(
    token = BuildConfig.GITHUB_TOKEN,
    owner = BuildConfig.GITHUB_OWNER,
    repo = BuildConfig.GITHUB_REPO,
)

MBAAndroid.setTicketBackends(
    notionBackend = notionBackend,
    githubBackend = githubBackend,
)
```

Set no backend when the app wants callback-only mode:

```kotlin
MBAAndroid.setTicketBackends()
```

Duplicate crashes should update the existing local bug group and external
record instead of creating a second parent ticket for the same fingerprint.

## 8. What Happens After A Crash

Fatal crash flow:

```text
1. The app crashes.
2. MBA writes a raw crash JSON file to app-private storage.
3. Android terminates the process normally.
4. The user opens the app again.
5. WorkManager processes pending crash files.
6. SDKOnly runs agent analysis or raw fallback.
7. Local grouping updates the matching bug group.
8. The app receives callbacks/JSON.
9. Optional Notion/GitHub sinks create or update external records.
```

ANR flow:

```text
1. Android terminates the previous process for ANR.
2. The user opens the app again.
3. MBA checks Android historical process exits on API 30+.
4. MBA writes an ANR raw report when a new ANR exit is found.
5. The normal SDKOnly worker pipeline processes it.
```

## 9. Release Build Notes

Crash capture does not depend on Logcat. In release builds:

- keep `debug = false`
- use callbacks, JSON payloads, or external sinks for observability
- keep provider keys out of source control
- add custom redaction patterns for app-specific sensitive formats
- avoid adding sensitive values to breadcrumbs or metadata

## 10. Minimal SDKOnly Checklist

- Add `mba-android`
- Add optional `mba-notion` and/or `mba-github` only if needed
- Configure `MBA` in `Application.onCreate`
- Call `MBAAndroid.saveConfig(...)`
- Register optional ticket backends
- Keep `sendToBackend = false`
- Set `useAgent = true` only when an LLM key is available
- Add safe screen names and breadcrumbs
- Use callbacks or JSON to handle results in the app layer
- Test fatal crash, non-fatal error, duplicate crash, and ANR restart flow
