# SDKOnly Android Implementation Guide

This guide shows how to add Mobile Bug Agent to an Android app in SDKOnly mode.
SDKOnly means the app captures and processes crashes locally, then the app
decides what to do with the result.

Use SDKOnly when you want:

- no MBA hosted backend
- app-owned LLM keys or local model endpoints
- app-layer callbacks or JSON payloads
- optional app-owned Notion/GitHub delivery
- local duplicate grouping before external ticket creation

Hosted/SaaS setup is separate. This guide focuses only on SDKOnly.

## 1. Add The SDK

For a published SDK:

```kotlin
dependencies {
    implementation("dev.sunnat629.mba:mba-android:<version>")
}
```

Before Maven Central is available, pre-release artifacts can be consumed from
GitHub Packages. See [GitHub Packages SDK integration](GITHUB_PACKAGES_SDK.md).

When working inside this repository:

```kotlin
dependencies {
    implementation(project(":mba-android"))
}
```

Only add external delivery modules if your app needs them:

```kotlin
dependencies {
    implementation("dev.sunnat629.mba:mba-notion:<version>")
    implementation("dev.sunnat629.mba:mba-github:<version>")
}
```

Repository form:

```kotlin
dependencies {
    implementation(project(":mba-notion"))
    implementation(project(":mba-github"))
}
```

You do not need to add `mba-core` or `mba-agent` directly when consuming the
Android SDK. `mba-android` brings the required core and agent pieces.

## 2. Configure One LLM

The simplest plug-and-play setup uses Gemini:

```properties
GEMINI_API_KEY=your_gemini_key
```

Your production app can use any secret source: BuildConfig, CI-injected values,
encrypted remote config, dependency injection, or another app-owned mechanism.
The SDK only needs an `LLMConfig`.

## 3. Initialize In `Application`

Install and configure MBA as early as possible.

```kotlin
import android.app.Application
import android.util.Log
import dev.sunnat629.mba.android.MBAAndroid
import dev.sunnat629.mba.core.MBA
import dev.sunnat629.mba.core.config.LLM
import dev.sunnat629.mba.core.config.MBAConfig
import dev.sunnat629.mba.core.config.MBAMode

class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        MBAAndroid.install(this)

        val llmConfig = LLM.gemini(BuildConfig.GEMINI_API_KEY)

        MBA.configure(
            MBAConfig.Builder().apply {
                mode = MBAMode.SdkOnly(llm = llmConfig)
                debug = BuildConfig.DEBUG
            }.build(),
        )

        MBAAndroid.saveConfig(
            context = this,
            llm = llmConfig,
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

        MBAAndroid.flushPendingCrashes(this)
    }
}
```

`MBAAndroid.install(this)` is idempotent. The Android artifact also includes
AndroidX Startup metadata, so install may already have run before
`Application.onCreate`; the explicit call keeps setup predictable if the app
disables AndroidX Startup.

SDKOnly defaults are `sendToBackend = false` and `useAgent = true`. Only pass
those flags when the app needs to override the default route.

## 4. Add Crash Context

MBA captures fatal crashes automatically. Add lightweight context where it helps
debugging.

```kotlin
MBA.setScreen("CheckoutScreen")
MBA.addBreadcrumb("Tapped Pay")
```

Log non-fatal errors explicitly:

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

Attach the coroutine handler where useful:

```kotlin
val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + MBA.exceptionHandler)
```

Treat crash context like logs. Do not put emails, access tokens, payment data,
raw user input, or private content in screen names, breadcrumbs, exception
messages, or custom metadata.

## 5. Choose Agent Or Raw Fallback

Use the Koog agent when you want richer reports:

```kotlin
MBAConfig.Builder().apply {
    mode = MBAMode.SdkOnly(llm = LLM.gemini(BuildConfig.GEMINI_API_KEY))
    useAgent = true
}
```

Use raw fallback when you do not want local LLM analysis:

```kotlin
MBAConfig.Builder().apply {
    mode = MBAMode.SdkOnly()
    useAgent = false
}

MBAAndroid.saveConfig(
    context = this,
    sendToBackend = false,
    llm = null,
    useAgent = false,
)
```

Raw fallback still gives the app structured crash data, device/app metadata,
fingerprint, local grouping, callbacks, JSON, and optional external delivery.
It does not infer richer reproduction steps or possible cause.

## 6. Use Any Supported Provider

The sample app stays simple with Gemini, but external apps are not bound to
Gemini. SDKOnly accepts any `LLMConfig` supported by the SDK.

```kotlin
val llmConfig = LLM.gemini(apiKey, model = "gemini-2.0-flash")
```

```kotlin
val llmConfig = LLM.openAI(apiKey, model = "gpt-4o-mini")
```

```kotlin
val llmConfig = LLM.anthropic(apiKey, model = "claude-sonnet-4-20250514")
```

```kotlin
val llmConfig = LLM.ollama(
    model = "llama3.2:latest",
    endpoint = "http://10.0.2.2:11434",
)
```

```kotlin
val llmConfig = LLM.openRouter(
    apiKey = apiKey,
    model = "anthropic/claude-3.5-sonnet",
)
```

```kotlin
val llmConfig = LLM.mistral(apiKey, model = "mistral-large-latest")
val llmConfig = LLM.deepSeek(apiKey, model = "deepseek-chat")
val llmConfig = LLM.dashScope(apiKey, model = "qwen-plus")
```

For OpenAI-compatible local or hosted gateways such as LM Studio, vLLM, LiteLLM,
or an app-owned proxy:

```kotlin
val llmConfig = LLM.custom(
    apiKey = "",
    endpoint = "http://10.0.2.2:1234/v1",
    model = "local-model",
)
```

Pass the same `llmConfig` to both `MBAMode.SdkOnly(...)` and
`MBAAndroid.saveConfig(...)`, then call `MBAAndroid.flushPendingCrashes(...)`
after optional sinks are registered so WorkManager can process pending crashes
after restart.

## 7. Handle Callback JSON

Callback-only mode is the smallest SDKOnly deployment. The SDK processes the
crash and hands the result back to the app.

```kotlin
MBAAndroid.saveConfig(
    context = this,
    llm = llmConfig,
    callback = { event ->
        // Latest processed event from this worker run.
    },
    batchCallback = { batch ->
        // All processed events from this worker run.
    },
    jsonCallback = { json ->
        // Latest event as JSON.
    },
    batchJsonCallback = { json ->
        // Full batch as JSON.
    },
)
```

Use latest callbacks for simple app behavior. Use batch callbacks when the app
needs every pending crash file processed during the worker run.

## 8. Add Notion Or GitHub

Notion and GitHub are optional. Add only the modules your app uses.
Callback-only mode is the safest default: MBA emits JSON and your app owns
delivery, retry, storage, and schema decisions.

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

For callback-only mode, do not register ticket backends:

```kotlin
MBAAndroid.setTicketBackends()
```

Repeated crashes should update the existing local bug group and external record
instead of creating duplicate parent tickets for the same fingerprint.

Detailed setup:

- [Notion integration guide](NOTION_INTEGRATION.md)
- [GitHub integration guide](GITHUB_INTEGRATION.md)

## 9. Crash And ANR Flow

Fatal crash flow:

```text
1. The app crashes.
2. MBA writes raw crash JSON to app-private storage.
3. Android terminates the process normally.
4. The user opens the app again.
5. The app restores SDKOnly config and optional sinks.
6. The app calls `MBAAndroid.flushPendingCrashes(...)`.
7. WorkManager processes pending crash files.
8. SDKOnly runs Koog analysis or raw fallback.
9. Local grouping updates the matching bug group.
10. The app receives callbacks/JSON.
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

## 10. Release Checklist

- Keep `debug = false` in release builds.
- Keep provider and integration keys out of source control.
- Use callback JSON or external sinks for observability instead of Logcat.
- Add custom redaction patterns for app-specific sensitive formats.
- Avoid sensitive values in breadcrumbs, screen names, exception messages, and
  custom metadata.
- Test fatal crash, non-fatal error, duplicate crash, raw fallback, and ANR
  restart flow.

## Minimal Setup Checklist

- Add `mba-android`.
- Configure `MBA` in `Application.onCreate`.
- Call `MBAAndroid.saveConfig(...)`.
- Pass an `LLMConfig`; SDKOnly defaults to `sendToBackend = false` and
  `useAgent = true`.
- Add safe screen names and breadcrumbs.
- Use callbacks or JSON to handle results in the app layer.
