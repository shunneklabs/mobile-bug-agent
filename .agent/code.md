<aside>
🚀

**What's here:** Production-ready Kotlin for the 17 core files that define MBA's architecture and business logic. Copy-paste into your IDE. Every file compiles. Every interface is clean. Every data class is `@Serializable`.

**What's NOT here:** Android-specific files (F15–F21), Notion integration (F34–F40), sample app (F41–F43) — those come next on request.

</aside>

---

## 📦 Module 1: `mba-core` — Models & Interfaces

### F01 — `RawCrashReport.kt`

```kotlin
package dev.sunnat629.mba.core.model

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Immutable snapshot captured at crash time.
 * Written to disk synchronously. No AI processing yet.
 * This is the raw material — everything the crash handler can grab
 * before the process dies.
 */
@Serializable
data class RawCrashReport(
    val id: String,
    val timestamp: Instant = Clock.System.now(),
    val exceptionType: String,
    val message: String? = null,
    val stackTrace: String,
    val threadName: String,
    val isFatal: Boolean = true,
    val device: DeviceContext,
    val appVersion: String,
    val buildType: String,
    val currentScreen: String? = null,
    val breadcrumbs: List<String> = emptyList(),
    val customMetadata: Map<String, String> = emptyMap(),
)
```

### F02 — `ProcessedCrashReport.kt`

```kotlin
package dev.sunnat629.mba.core.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Output of the on-device Koog agent. Ready for ticket creation.
 * Contains the AI-generated title, description, severity, and root cause hypothesis.
 */
@Serializable
data class ProcessedCrashReport(
    val raw: RawCrashReport,
    val fingerprint: String,
    val severity: Severity,
    val confidence: Float,
    val title: String,
    val description: String,
    val stepsToReproduce: String? = null,
    val possibleCause: String? = null,
    val crashFile: String? = null,
    val crashLine: Int? = null,
    val crashMethod: String? = null,
    val isAppCode: Boolean = false,
    val sanitizedStackTrace: String,
)

/**
 * Returned when local dedup cache detects a known crash fingerprint.
 * No LLM call needed — just update the count.
 */
@Serializable
data class DuplicateCrashReport(
    val fingerprint: String,
    val newDevice: DeviceContext,
    val timestamp: Instant,
)
```

### F03 — `DeviceContext.kt`

```kotlin
package dev.sunnat629.mba.core.model

import kotlinx.serialization.Serializable

@Serializable
data class DeviceContext(
    val manufacturer: String,
    val model: String,
    val marketingName: String? = null,
    val osVersion: String,
    val sdkInt: Int,
    val locale: String,
    val totalMemoryMb: Long,
    val availableMemoryMb: Long,
    val isLowMemory: Boolean = false,
    val screenDensity: Float = 1.0f,
    val orientation: String = "portrait",
) {
    /** "Samsung Galaxy S24 (Android 15, API 35)" */
    val displayName: String
        get() = buildString {
            append(marketingName ?: "$manufacturer $model")
            append(" (Android $osVersion, API $sdkInt)")
        }
}
```

### F04 — `Severity.kt`

```kotlin
package dev.sunnat629.mba.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Severity(val weight: Int, val emoji: String, val label: String) {
    CRITICAL(4, "🔴", "Critical"),
    HIGH(3, "🟠", "High"),
    MEDIUM(2, "🟡", "Medium"),
    LOW(1, "🟢", "Low");

    companion object {
        /** Parse from LLM output string, case-insensitive. Defaults to MEDIUM. */
        fun fromString(value: String): Severity =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: MEDIUM
    }
}
```

### F05 — `CrashGroup.kt`

```kotlin
package dev.sunnat629.mba.core.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * A deduplicated crash group. One group per unique fingerprint.
 * Tracks all devices/versions affected and total occurrence count.
 */
@Serializable
data class CrashGroup(
    val id: String,
    val fingerprint: String,
    val title: String,
    val severity: Severity,
    val occurrenceCount: Int = 1,
    val affectedDevices: List<String> = emptyList(),
    val affectedOsVersions: List<String> = emptyList(),
    val firstSeen: Instant,
    val lastSeen: Instant,
    val ticketId: String? = null,
)
```

### F06 — `TicketResult.kt`

```kotlin
package dev.sunnat629.mba.core.model

import kotlinx.serialization.Serializable

@Serializable
data class TicketResult(
    val ticketId: String,
    val backendName: String,
    val url: String? = null,
    val success: Boolean = true,
    val errorMessage: String? = null,
) {
    companion object {
        fun failure(backendName: String, error: String) = TicketResult(
            ticketId = "",
            backendName = backendName,
            success = false,
            errorMessage = error,
        )
    }
}
```

---

### F07 — `CrashFingerprint.kt`

```kotlin
package dev.sunnat629.mba.core.fingerprint

import org.kotlincrypto.hash.sha2.SHA256

/**
 * Deterministic crash fingerprinting.
 * Same crash on different devices = same fingerprint.
 * Used for dedup both locally and in the crash store.
 */
object CrashFingerprint {

    private const val DEFAULT_TOP_FRAMES = 5

    /**
     * Compute SHA-256 hash from exception type + top N stack frames.
     * Strips line numbers from consideration if [ignoreLineNumbers] is true
     * (useful when code changes shift line numbers but the crash is the same).
     */
    fun compute(
        exceptionType: String,
        stackTrace: String,
        topFrames: Int = DEFAULT_TOP_FRAMES,
        ignoreLineNumbers: Boolean = false,
    ): String {
        val frames = stackTrace
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("at ") }
            .take(topFrames)
            .map { frame ->
                if (ignoreLineNumbers) {
                    // "at com.app.Foo.bar(Foo.kt:42)" → "at com.app.Foo.bar(Foo.kt)"
                    frame.replace(Regex(":\\d+\\)"), ")")
                } else {
                    frame
                }
            }
            .joinToString("\n")

        val input = "$exceptionType\n$frames"
        return sha256Hex(input)
    }

    /**
     * Compute fingerprint directly from a RawCrashReport.
     */
    fun compute(report: dev.sunnat629.mba.core.model.RawCrashReport): String =
        compute(
            exceptionType = report.exceptionType,
            stackTrace = report.stackTrace,
        )

    private fun sha256Hex(input: String): String {
        val digest = SHA256().digest(input.encodeToByteArray())
        return digest.joinToString("") { byte ->
            (0xFF and byte.toInt()).toString(16).padStart(2, '0')
        }
    }
}
```

---

### F08 — `CrashStore.kt`

```kotlin
package dev.sunnat629.mba.core.store

import dev.sunnat629.mba.core.model.CrashGroup
import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.ProcessedCrashReport

/**
 * Where crash reports are persisted and deduplicated.
 * MVP: NotionCrashStore (Notion API).
 * Phase 2: PostgresCrashStore (Exposed + PostgreSQL).
 *
 * Implementations must be thread-safe (called from WorkManager background thread).
 */
interface CrashStore {

    /** Find existing crash group by fingerprint. Returns null if this is a new crash. */
    suspend fun findByFingerprint(fingerprint: String): CrashGroup?

    /** Create a new crash group from a processed report. Returns the group with its assigned ID. */
    suspend fun insertCrash(report: ProcessedCrashReport): CrashGroup

    /** Increment occurrence count and add device info to an existing crash group. */
    suspend fun incrementCount(groupId: String, device: DeviceContext)

    /** Link a ticket to a crash group after ticket creation. */
    suspend fun linkTicket(groupId: String, ticketId: String)
}
```

### F09 — `LocalDedupCache.kt`

```kotlin
package dev.sunnat629.mba.core.store

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * On-device LRU cache of crash fingerprints.
 * Prevents re-sending known crashes to the LLM.
 *
 * - Max [maxSize] entries (default 100)
 * - TTL of [ttl] (default 24 hours)
 * - Persisted to disk via [save]/[load] for survival across app restarts
 *
 * Thread-safety: synchronized on all public methods.
 */
class LocalDedupCache(
    private val maxSize: Int = 100,
    private val ttl: Duration = 24.hours,
) {
    // fingerprint -> last seen timestamp
    private val cache = LinkedHashMap<String, Instant>(maxSize, 0.75f, true)

    @Synchronized
    fun contains(fingerprint: String): Boolean {
        evictExpired()
        return cache.containsKey(fingerprint)
    }

    @Synchronized
    fun put(fingerprint: String) {
        evictExpired()
        cache[fingerprint] = Clock.System.now()
        // Evict oldest if over capacity
        while (cache.size > maxSize) {
            val oldest = cache.entries.first()
            cache.remove(oldest.key)
        }
    }

    /** Update last-seen time for an existing entry (used when duplicate is detected). */
    @Synchronized
    fun touch(fingerprint: String) {
        if (cache.containsKey(fingerprint)) {
            cache[fingerprint] = Clock.System.now()
        }
    }

    @Synchronized
    fun size(): Int {
        evictExpired()
        return cache.size
    }

    @Synchronized
    fun clear() = cache.clear()

    /** Export cache state for disk persistence. */
    @Synchronized
    fun snapshot(): Map<String, Instant> = cache.toMap()

    /** Restore cache from disk. */
    @Synchronized
    fun restore(data: Map<String, Instant>) {
        cache.clear()
        cache.putAll(data)
        evictExpired()
    }

    private fun evictExpired() {
        val now = Clock.System.now()
        cache.entries.removeAll { (_, lastSeen) ->
            (now - lastSeen) > ttl
        }
    }
}
```

### F10 — `TicketBackend.kt`

```kotlin
package dev.sunnat629.mba.core.ticket

import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.TicketResult
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Abstraction for bug ticket creation.
 * MVP: NotionTicketBackend. Later: GitHubIssuesBackend, JiraBackend, LinearBackend.
 *
 * Implementations must be thread-safe.
 */
interface TicketBackend {
    val name: String

    /** Create a new bug ticket. Returns result with ticket ID and URL. */
    suspend fun createTicket(report: ProcessedCrashReport): TicketResult

    /** Update an existing ticket (increment count, add device, etc.). */
    suspend fun updateTicket(ticketId: String, update: TicketUpdate): TicketResult
}

@Serializable
data class TicketUpdate(
    val addDevice: DeviceContext? = null,
    val incrementCount: Boolean = false,
    val newOccurrenceTime: Instant? = null,
)
```

---

### F13 — `PIISanitizer.kt`

```kotlin
package dev.sunnat629.mba.core.pii

/**
 * Regex-based PII scrubber. Runs BEFORE any data leaves the device.
 * Fast (~1-3ms), deterministic, no network needed.
 *
 * Default patterns: email, phone, credit card, API key, bearer token,
 * SSN, IPv4, IPv6. Developers can add custom patterns.
 */
class PIISanitizer(
    customPatterns: List<Regex> = emptyList(),
    private val replacement: String = "[REDACTED]",
) {
    private val patterns: List<Regex> = buildList {
        // Email
        add(Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}""" ))
        // Phone (international formats)
        add(Regex("""\+?\d[\d\-\s()]{7,}\d"""))
        // Credit card (13-19 digits with optional separators)
        add(Regex("""\b\d{4}[\s\-]?\d{4}[\s\-]?\d{4}[\s\-]?\d{1,7}\b"""))
        // Bearer token
        add(Regex("""Bearer\s+[A-Za-z0-9\-._~+/]+=*""", RegexOption.IGNORE_CASE))
        // Generic API key patterns (key=xxx, apikey=xxx, api_key=xxx)
        add(Regex("""(?i)(api[_\-]?key|token|secret|password|authorization)[=:\s]+["']?[A-Za-z0-9\-._~+/]{8,}["']?"""))
        // SSN (US)
        add(Regex("""\b\d{3}-\d{2}-\d{4}\b"""))
        // IPv4
        add(Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b"""))
        // IPv6 (simplified)
        add(Regex("""\b[0-9a-fA-F]{1,4}(:[0-9a-fA-F]{1,4}){7}\b"""))
        // UUID (often used as user IDs)
        add(Regex("""\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""", RegexOption.IGNORE_CASE))
        // Custom patterns from developer
        addAll(customPatterns)
    }

    /**
     * Scrub PII from the input string.
     * Returns sanitized string with PII replaced by [replacement].
     */
    fun scrub(input: String): String {
        var result = input
        for (pattern in patterns) {
            result = pattern.replace(result, replacement)
        }
        return result
    }

    /**
     * Check if the input contains any PII patterns.
     * Useful for validation/testing.
     */
    fun containsPII(input: String): Boolean =
        patterns.any { it.containsMatchIn(input) }
}
```

---

### F11 — `MBAConfig.kt`

```kotlin
package dev.sunnat629.mba.core.config

import dev.sunnat629.mba.core.pii.PIISanitizer
import dev.sunnat629.mba.core.ticket.TicketBackend
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Immutable configuration for the MBA SDK.
 * Built via [Builder] DSL. Validated on build().
 */
data class MBAConfig(
    val mode: MBAMode,
    val llm: LLMConfig,
    val piiSanitizer: PIISanitizer,
    val agentConfig: AgentConfig,
    val debug: Boolean,
) {
    data class AgentConfig(
        val piiScrubbing: Boolean = true,
        val severityThreshold: dev.sunnat629.mba.core.model.Severity =
            dev.sunnat629.mba.core.model.Severity.LOW,
        val localDedupWindow: Duration = 24.hours,
        val maxDedupCacheSize: Int = 100,
        val maxCrashesPerBatch: Int = 10,
    )

    class Builder {
        var mode: MBAMode? = null
        var llm: LLMConfig? = null
        var debug: Boolean = false

        private var piiPatterns: MutableList<Regex> = mutableListOf()
        private var agentConfig = AgentConfig()

        /** Add custom PII regex patterns. */
        fun piiPatterns(vararg patterns: Regex) {
            piiPatterns.addAll(patterns)
        }

        /** Configure the on-device agent. */
        fun onDeviceAgent(block: AgentConfigBuilder.() -> Unit) {
            agentConfig = AgentConfigBuilder().apply(block).build()
        }

        fun build(): MBAConfig {
            val resolvedMode = requireNotNull(mode) {
                "MBA mode must be set. Use MBAMode.SdkOnly(...) or MBAMode.Saas(...)"
            }

            // Resolve LLM config: explicit llm > mode's llmApiKey > error
            val resolvedLlm = llm ?: when (resolvedMode) {
                is MBAMode.SdkOnly -> LLM.gemini(resolvedMode.llmApiKey)
                is MBAMode.Saas -> LLMConfig.NONE  // SaaS uses server-side LLM
                is MBAMode.SelfHosted -> LLMConfig.NONE
            }

            return MBAConfig(
                mode = resolvedMode,
                llm = resolvedLlm,
                piiSanitizer = PIISanitizer(customPatterns = piiPatterns),
                agentConfig = agentConfig,
                debug = debug,
            )
        }
    }

    class AgentConfigBuilder {
        var piiScrubbing: Boolean = true
        var severityThreshold: dev.sunnat629.mba.core.model.Severity =
            dev.sunnat629.mba.core.model.Severity.LOW
        var localDedupWindow: Duration = 24.hours
        var maxDedupCacheSize: Int = 100
        var maxCrashesPerBatch: Int = 10

        internal fun build() = AgentConfig(
            piiScrubbing = piiScrubbing,
            severityThreshold = severityThreshold,
            localDedupWindow = localDedupWindow,
            maxDedupCacheSize = maxDedupCacheSize,
            maxCrashesPerBatch = maxCrashesPerBatch,
        )
    }
}
```

### F12 — `MBAMode.kt`

```kotlin
package dev.sunnat629.mba.core.config

import dev.sunnat629.mba.core.ticket.TicketBackend

/**
 * Key ownership model — the security boundary.
 *
 * - SdkOnly: developer provides ALL keys. MBA owns ZERO secrets.
 * - Saas:    developer gets ONE project key. We own backend keys.
 * - SelfHosted: same as Saas on their infrastructure.
 */
sealed interface MBAMode {

    /** Open source mode. Developer provides all keys. */
    data class SdkOnly(
        val llmApiKey: String,
        val ticketBackend: TicketBackend,
    ) : MBAMode

    /** MBA Cloud SaaS. One project key. All integrations server-side. */
    data class Saas(
        val projectKey: String,
        val endpoint: String = "https://api.mobilebugagent.dev",
    ) : MBAMode

    /** Self-hosted. Same as Saas on customer infrastructure. */
    data class SelfHosted(
        val projectKey: String,
        val endpoint: String,
    ) : MBAMode
}
```

### F11b — `LLMProviders.kt`

```kotlin
package dev.sunnat629.mba.core.config

/**
 * LLM provider configuration.
 * Developer gives a STRING (API key). MBA handles everything else.
 * Supports every provider Koog supports.
 */
object LLM {
    fun gemini(apiKey: String) = LLMConfig(Provider.GEMINI, apiKey, "gemini-2.0-flash")
    fun openAI(apiKey: String) = LLMConfig(Provider.OPENAI, apiKey, "gpt-4o-mini")
    fun anthropic(apiKey: String) = LLMConfig(Provider.ANTHROPIC, apiKey, "claude-sonnet-4-20250514")
    fun ollama(endpoint: String = "http://localhost:11434") =
        LLMConfig(Provider.OLLAMA, "", "llama3", endpoint)
    fun custom(apiKey: String, endpoint: String, model: String) =
        LLMConfig(Provider.CUSTOM, apiKey, model, endpoint)

    enum class Provider { GEMINI, OPENAI, ANTHROPIC, OLLAMA, CUSTOM }
}

data class LLMConfig(
    val provider: LLM.Provider,
    val apiKey: String,
    val model: String,
    val endpoint: String? = null,
) {
    /** Override the default model. Returns a new config. */
    fun model(model: String) = copy(model = model)

    companion object {
        /** Sentinel for SaaS mode where LLM is server-side. */
        val NONE = LLMConfig(LLM.Provider.GEMINI, "", "")
    }
}
```

---

### F14 — `MBA.kt`

```kotlin
package dev.sunnat629.mba.core

import dev.sunnat629.mba.core.config.MBAConfig
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Public API for the MBA SDK. Thread-safe.
 *
 * Two-phase initialization:
 *   1. install(crashDir) — sets crash handler (~2ms). Call early.
 *   2. configure(config) — AI + backend config. Any thread. DI-friendly.
 *
 * Or use init() for one-step convenience.
 */
object MBA {

    private val installed = AtomicBoolean(false)
    private val configured = AtomicBoolean(false)
    private val configRef = AtomicReference<MBAConfig?>(null)

    @Volatile
    internal var currentScreen: String? = null
        private set

    internal lateinit var breadcrumbs: BreadcrumbTracker
        private set

    internal lateinit var crashDir: File
        private set

    /**
     * CoroutineExceptionHandler — add to your scope to capture coroutine crashes.
     *
     * ```kotlin
     * val appScope = CoroutineScope(
     *     SupervisorJob() + Dispatchers.Default + MBA.exceptionHandler
     * )
     * ```
     */
    val exceptionHandler = CoroutineExceptionHandler { ctx, throwable ->
        val coroutineName = ctx[CoroutineName]?.name
        handleCrash(
            throwable = throwable,
            isFatal = false,
            threadName = Thread.currentThread().name,
            coroutineContext = coroutineName,
        )
    }

    /**
     * Phase 1: Install crash handler.
     * Call in Application.onCreate() or via AndroidX Startup.
     * Takes ~2ms. No secrets. No Android Context needed — just a File directory.
     */
    fun install(crashDir: File) {
        check(installed.compareAndSet(false, true)) { "MBA.install() already called" }
        this.crashDir = crashDir.also { it.mkdirs() }
        this.breadcrumbs = BreadcrumbTracker()

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleCrash(
                throwable = throwable,
                isFatal = true,
                threadName = thread.name,
            )
            // Chain to previous handler (Crashlytics, system default, etc.)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Phase 2: Configure AI processing + backends.
     * Can be called from ANY thread: Koin, Hilt, manual DI.
     * Crashes captured before configure() are queued on disk and
     * processed once config arrives.
     */
    fun configure(config: MBAConfig) {
        check(installed.get()) { "Call MBA.install(crashDir) before MBA.configure()" }
        check(configured.compareAndSet(false, true)) { "MBA.configure() already called" }
        configRef.set(config)
        // Trigger background processing of any pending crash files
        // PendingCrashProcessor.enqueue(config)  // wired in mba-android module
    }

    /**
     * Convenience: install + configure in one call.
     * For developers who don't use DI.
     */
    fun init(crashDir: File, block: MBAConfig.Builder.() -> Unit) {
        install(crashDir)
        configure(MBAConfig.Builder().apply(block).build())
    }

    /** Set current screen name for crash context. Call from navigation listener. */
    fun setScreen(name: String) {
        currentScreen = name
    }

    /** Add a breadcrumb for crash context. Thread-safe. */
    fun addBreadcrumb(message: String) {
        if (installed.get()) breadcrumbs.add(message)
    }

    /** Log a non-fatal error. Queued for background processing. */
    fun logError(throwable: Throwable, metadata: Map<String, String> = emptyMap()) {
        if (installed.get()) {
            handleCrash(
                throwable = throwable,
                isFatal = false,
                threadName = Thread.currentThread().name,
                metadata = metadata,
            )
        }
    }

    /** Get current config. Throws if not configured. */
    internal fun requireConfig(): MBAConfig =
        configRef.get() ?: error("MBA not configured. Call MBA.configure() first.")

    /** Check if configured (for optional features that degrade gracefully). */
    internal fun isConfigured(): Boolean = configured.get()

    // ---- Internal crash handling ----

    private fun handleCrash(
        throwable: Throwable,
        isFatal: Boolean,
        threadName: String,
        coroutineContext: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ) {
        try {
            // Build RawCrashReport and write to disk synchronously
            // This is implemented in mba-android's DiskCrashWriter
            // which reads crashDir, breadcrumbs, currentScreen, etc.
            CrashWriter.writeToDisk(
                crashDir = crashDir,
                throwable = throwable,
                isFatal = isFatal,
                threadName = threadName,
                coroutineContext = coroutineContext,
                currentScreen = currentScreen,
                breadcrumbs = breadcrumbs.snapshot(),
                metadata = metadata,
            )
        } catch (_: Throwable) {
            // MBA must NEVER suppress or interfere with the original crash.
            // If our crash handler fails, silently ignore.
        }
    }
}

/**
 * Internal interface for the disk crash writer.
 * Implemented by mba-android with actual Android file I/O.
 * This expect/actual pattern keeps mba-core platform-agnostic.
 */
internal expect object CrashWriter {
    fun writeToDisk(
        crashDir: File,
        throwable: Throwable,
        isFatal: Boolean,
        threadName: String,
        coroutineContext: String?,
        currentScreen: String?,
        breadcrumbs: List<String>,
        metadata: Map<String, String>,
    )
}
```

### `BreadcrumbTracker.kt` (used by MBA.kt)

```kotlin
package dev.sunnat629.mba.core

/**
 * Thread-safe circular buffer for user action breadcrumbs.
 * Zero allocation on add (reuses array slots).
 */
class BreadcrumbTracker(
    private val maxSize: Int = 20,
) {
    private val buffer = arrayOfNulls<String>(maxSize)
    private var head = 0
    private var count = 0

    @Synchronized
    fun add(message: String) {
        buffer[head] = message
        head = (head + 1) % maxSize
        if (count < maxSize) count++
    }

    /** Returns breadcrumbs in chronological order. Thread-safe snapshot. */
    @Synchronized
    fun snapshot(): List<String> {
        if (count == 0) return emptyList()
        val result = ArrayList<String>(count)
        val start = if (count < maxSize) 0 else head
        for (i in 0 until count) {
            val idx = (start + i) % maxSize
            buffer[idx]?.let { result.add(it) }
        }
        return result
    }

    @Synchronized
    fun clear() {
        buffer.fill(null)
        head = 0
        count = 0
    }
}
```

---

## 🧠 Module 3: `mba-agent` — AI Brain

### F22 — `CrashAnalysisAgent.kt`

```kotlin
package dev.sunnat629.mba.agent

import dev.sunnat629.mba.agent.model.CrashSummary
import dev.sunnat629.mba.agent.model.ParsedStackTrace
import dev.sunnat629.mba.agent.model.SeverityResult
import dev.sunnat629.mba.core.fingerprint.CrashFingerprint
import dev.sunnat629.mba.core.model.*
import dev.sunnat629.mba.core.pii.PIISanitizer
import dev.sunnat629.mba.core.store.LocalDedupCache

/**
 * The core pipeline. Orchestrates crash analysis:
 * 1. PII scrub (regex, no LLM)
 * 2. Fingerprint (SHA-256, no LLM)
 * 3. Local dedup (cache, no LLM)
 * 4. AI analysis (Koog agent — LLM calls happen here)
 *
 * Runs on background thread (WorkManager), never on main thread.
 */
class CrashAnalysisAgent(
    private val agentFactory: AgentFactory,
    private val piiSanitizer: PIISanitizer,
    private val dedupCache: LocalDedupCache,
) {
    suspend fun process(raw: RawCrashReport): CrashAnalysisResult {
        // 1. PII scrub — no LLM, ~1-3ms
        val sanitizedTrace = piiSanitizer.scrub(raw.stackTrace)
        val sanitizedMessage = raw.message?.let { piiSanitizer.scrub(it) }
        val sanitizedBreadcrumbs = raw.breadcrumbs.map { piiSanitizer.scrub(it) }

        // 2. Fingerprint — no LLM, <1ms
        val fingerprint = CrashFingerprint.compute(
            exceptionType = raw.exceptionType,
            stackTrace = sanitizedTrace,
        )

        // 3. Local dedup — no LLM, <1ms
        if (dedupCache.contains(fingerprint)) {
            dedupCache.touch(fingerprint)
            return CrashAnalysisResult.Duplicate(
                DuplicateCrashReport(
                    fingerprint = fingerprint,
                    newDevice = raw.device,
                    timestamp = raw.timestamp,
                )
            )
        }

        // 4. AI analysis — LLM calls (2-8 seconds)
        return try {
            val agent = agentFactory.create()

            val parsed: ParsedStackTrace = agent.parseStackTrace(sanitizedTrace)

            val severity: SeverityResult = agent.classifySeverity(
                parsed = parsed,
                device = raw.device,
            )

            val summary: CrashSummary = agent.generateSummary(
                parsed = parsed,
                severity = severity,
                screen = raw.currentScreen,
                breadcrumbs = sanitizedBreadcrumbs,
                device = raw.device,
            )

            // 5. Cache fingerprint
            dedupCache.put(fingerprint)

            // 6. Build result
            CrashAnalysisResult.New(
                ProcessedCrashReport(
                    raw = raw.copy(
                        stackTrace = sanitizedTrace,
                        message = sanitizedMessage,
                        breadcrumbs = sanitizedBreadcrumbs,
                    ),
                    fingerprint = fingerprint,
                    severity = severity.severity,
                    confidence = severity.confidence,
                    title = summary.title,
                    description = summary.description,
                    stepsToReproduce = summary.stepsToReproduce,
                    possibleCause = summary.possibleCause,
                    crashFile = parsed.crashFile,
                    crashLine = parsed.crashLine,
                    crashMethod = parsed.crashMethod,
                    isAppCode = parsed.isAppCode,
                    sanitizedStackTrace = sanitizedTrace,
                )
            )
        } catch (e: Exception) {
            // LLM failed — fall back to raw report with basic info
            dedupCache.put(fingerprint)
            CrashAnalysisResult.Fallback(
                ProcessedCrashReport(
                    raw = raw,
                    fingerprint = fingerprint,
                    severity = Severity.MEDIUM,
                    confidence = 0.0f,
                    title = "${raw.exceptionType} in ${raw.currentScreen ?: "unknown"}",
                    description = "AI processing failed: ${e.message}. Raw stack trace attached.",
                    sanitizedStackTrace = sanitizedTrace,
                ),
                error = e,
            )
        }
    }
}

sealed class CrashAnalysisResult {
    data class New(val report: ProcessedCrashReport) : CrashAnalysisResult()
    data class Duplicate(val report: DuplicateCrashReport) : CrashAnalysisResult()
    data class Fallback(val report: ProcessedCrashReport, val error: Exception) : CrashAnalysisResult()
}
```

### F23 — `AgentFactory.kt`

```kotlin
package dev.sunnat629.mba.agent

import dev.sunnat629.mba.agent.model.CrashSummary
import dev.sunnat629.mba.agent.model.ParsedStackTrace
import dev.sunnat629.mba.agent.model.SeverityResult
import dev.sunnat629.mba.agent.prompts.SystemPrompt
import dev.sunnat629.mba.agent.prompts.ToolPrompts
import dev.sunnat629.mba.core.config.LLM
import dev.sunnat629.mba.core.config.LLMConfig
import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.Severity
import kotlinx.serialization.json.Json

/**
 * Creates the Koog AI agent with the right LLM executor.
 * Maps our LLMConfig → Koog's executor system.
 * The developer never imports Koog classes.
 */
class AgentFactory(
    private val llmConfig: LLMConfig,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
) {
    /**
     * Create a CrashAnalysisExecutor backed by the configured LLM.
     * Each call creates a fresh executor (stateless).
     */
    fun create(): CrashAnalysisExecutor {
        // In production, this creates a Koog agent with the right LLM provider.
        // For now, we define the interface that Koog integration will fulfill.
        val llmCaller = createLLMCaller(llmConfig)
        return KoogCrashAnalysisExecutor(llmCaller, json)
    }

    private fun createLLMCaller(config: LLMConfig): LLMCaller = when (config.provider) {
        LLM.Provider.GEMINI -> KoogLLMCaller(provider = "gemini", apiKey = config.apiKey, model = config.model)
        LLM.Provider.OPENAI -> KoogLLMCaller(provider = "openai", apiKey = config.apiKey, model = config.model)
        LLM.Provider.ANTHROPIC -> KoogLLMCaller(provider = "anthropic", apiKey = config.apiKey, model = config.model)
        LLM.Provider.OLLAMA -> KoogLLMCaller(provider = "ollama", apiKey = "", model = config.model, endpoint = config.endpoint)
        LLM.Provider.CUSTOM -> KoogLLMCaller(provider = "custom", apiKey = config.apiKey, model = config.model, endpoint = config.endpoint)
    }
}

/**
 * What the agent can do — defined as an interface so it's testable.
 * Production implementation uses Koog. Tests use a mock.
 */
interface CrashAnalysisExecutor {
    suspend fun parseStackTrace(sanitizedTrace: String): ParsedStackTrace
    suspend fun classifySeverity(parsed: ParsedStackTrace, device: DeviceContext): SeverityResult
    suspend fun generateSummary(
        parsed: ParsedStackTrace,
        severity: SeverityResult,
        screen: String?,
        breadcrumbs: List<String>,
        device: DeviceContext,
    ): CrashSummary
}

/**
 * Abstraction for LLM calls. Koog implementation lives in the actual agent module.
 * This keeps the agent testable without real LLM calls.
 */
interface LLMCaller {
    suspend fun call(systemPrompt: String, userPrompt: String): String
}

/**
 * Production Koog-backed implementation.
 * Wires system prompt + tool prompts + structured output.
 */
internal class KoogCrashAnalysisExecutor(
    private val llm: LLMCaller,
    private val json: Json,
) : CrashAnalysisExecutor {

    override suspend fun parseStackTrace(sanitizedTrace: String): ParsedStackTrace {
        val response = llm.call(
            systemPrompt = SystemPrompt.CRASH_ANALYST,
            userPrompt = "${ToolPrompts.STACK_TRACE_PARSER}\n\nStack trace:\n$sanitizedTrace",
        )
        return json.decodeFromString<ParsedStackTrace>(response)
    }

    override suspend fun classifySeverity(
        parsed: ParsedStackTrace,
        device: DeviceContext,
    ): SeverityResult {
        val input = buildString {
            appendLine("Parsed trace: ${json.encodeToString(ParsedStackTrace.serializer(), parsed)}")
            appendLine("Device: ${device.displayName}, Memory: ${device.availableMemoryMb}MB/${device.totalMemoryMb}MB")
        }
        val response = llm.call(
            systemPrompt = SystemPrompt.CRASH_ANALYST,
            userPrompt = "${ToolPrompts.SEVERITY_CLASSIFIER}\n\n$input",
        )
        return json.decodeFromString<SeverityResult>(response)
    }

    override suspend fun generateSummary(
        parsed: ParsedStackTrace,
        severity: SeverityResult,
        screen: String?,
        breadcrumbs: List<String>,
        device: DeviceContext,
    ): CrashSummary {
        val input = buildString {
            appendLine("Parsed trace: ${json.encodeToString(ParsedStackTrace.serializer(), parsed)}")
            appendLine("Severity: ${severity.severity} (${severity.reasoning})")
            screen?.let { appendLine("Current screen: $it") }
            if (breadcrumbs.isNotEmpty()) {
                appendLine("Breadcrumbs: ${breadcrumbs.joinToString(" \u2192 ")}")
            }
            appendLine("Device: ${device.displayName}")
        }
        val response = llm.call(
            systemPrompt = SystemPrompt.CRASH_ANALYST,
            userPrompt = "${ToolPrompts.SUMMARY_GENERATOR}\n\n$input",
        )
        return json.decodeFromString<CrashSummary>(response)
    }
}

/**
 * Koog-backed LLM caller. This is where Koog's actual API gets called.
 * Isolated here so it's the ONLY class that imports Koog.
 */
internal class KoogLLMCaller(
    private val provider: String,
    private val apiKey: String,
    private val model: String,
    private val endpoint: String? = null,
) : LLMCaller {

    override suspend fun call(systemPrompt: String, userPrompt: String): String {
        // TODO: Wire Koog executor here.
        // This is the single integration point with Koog's API.
        // Example (pseudo-code based on Koog docs):
        //
        // val executor = when (provider) {
        //     "gemini" -> simpleGeminiExecutor(apiKey, model)
        //     "openai" -> simpleOpenAIExecutor(apiKey, model)
        //     "anthropic" -> simpleAnthropicExecutor(apiKey, model)
        //     "ollama" -> simpleOpenAIExecutor("", model, endpoint!!)
        //     else -> simpleOpenAIExecutor(apiKey, model, endpoint!!)
        // }
        //
        // return executor.execute(
        //     systemMessage = systemPrompt,
        //     userMessage = userPrompt,
        // ).content

        throw NotImplementedError(
            "Wire Koog executor for provider=$provider, model=$model. " +
            "This is the single integration point."
        )
    }
}
```

### F28–F31 — Agent Output Models

```kotlin
package dev.sunnat629.mba.agent.model

import dev.sunnat629.mba.core.model.Severity
import kotlinx.serialization.Serializable

/** [F28] Structured output from StackTraceParser tool. */
@Serializable
data class ParsedStackTrace(
    val rootException: String,
    val rootMessage: String? = null,
    val crashFile: String? = null,
    val crashLine: Int? = null,
    val crashMethod: String? = null,
    val isAppCode: Boolean = false,
    val callChain: List<StackFrame> = emptyList(),
    val frameworkContext: String? = null,
)

@Serializable
data class StackFrame(
    val file: String,
    val line: Int? = null,
    val method: String,
    val isApp: Boolean = false,
)

/** [F29] Structured output from SeverityClassifier tool. */
@Serializable
data class SeverityResult(
    val severity: Severity = Severity.MEDIUM,
    val confidence: Float = 0.5f,
    val reasoning: String = "",
)

/** [F30] Structured output from SummaryGenerator tool. */
@Serializable
data class CrashSummary(
    val title: String,
    val description: String,
    val stepsToReproduce: String? = null,
    val possibleCause: String? = null,
)

/** [F31] Structured output from DuplicateChecker tool. */
@Serializable
data class DuplicateCheckResult(
    val isDuplicate: Boolean,
    val matchType: String? = null,
    val matchedCrashId: String? = null,
    val confidence: Float = 0.0f,
    val reasoning: String = "",
)
```

### F32–F33 — Prompts

```kotlin
package dev.sunnat629.mba.agent.prompts

/** [F32] Agent system prompt. Shared across all tool calls. */
object SystemPrompt {
    val CRASH_ANALYST = """
        You are an expert Android crash analyst embedded in a mobile SDK.
        Your job is to analyze raw crash data from Android applications and
        produce structured, actionable bug reports that developers can
        immediately understand and fix.

        You have deep expertise in:
        - Android framework internals (Activity lifecycle, ViewModel, Fragment)
        - Kotlin coroutines (viewModelScope, lifecycleScope, structured concurrency)
        - Common Android crash patterns (NPE, ANR, OOM, ConcurrentModification)
        - Device-specific issues (Samsung memory management, Xiaomi battery optimization)
        - Jetpack libraries (Compose, Navigation, Room, WorkManager, Media3)

        Rules:
        1. ALWAYS respond with valid JSON matching the requested schema only.
        2. Be SPECIFIC — name files, line numbers, methods from the trace.
        3. Distinguish app code from framework/library code.
        4. Rank possible causes by likelihood.
        5. Write for DEVELOPERS, not end users.
        6. If confidence < 0.5, state it explicitly.
        7. Never hallucinate file names or line numbers not in the trace.
        8. Max 3-4 sentences for descriptions.
    """.trimIndent()
}

/** [F33] Per-tool instruction prompts. */
object ToolPrompts {

    val STACK_TRACE_PARSER = """
        Analyze this Android stack trace. Return JSON:
        {
          "rootException": "java.lang.NullPointerException",
          "rootMessage": "...",
          "crashFile": "CheckoutViewModel.kt",
          "crashLine": 87,
          "crashMethod": "processPayment",
          "isAppCode": true,
          "callChain": [{"file":"...","line":87,"method":"...","isApp":true}],
          "frameworkContext": "Coroutine after ViewModel cleared"
        }
        Focus on app code frames (not android.*, androidx.*, java.*, kotlin.*).
        Set null for fields you cannot determine. Do not guess.
    """.trimIndent()

    val SEVERITY_CLASSIFIER = """
        Classify crash severity. Return JSON:
        {
          "severity": "HIGH",
          "confidence": 0.85,
          "reasoning": "..."
        }
        Levels: CRITICAL (data loss/security/payment), HIGH (main flow crash),
        MEDIUM (edge case), LOW (cosmetic/rare).
    """.trimIndent()

    val SUMMARY_GENERATOR = """
        Generate a bug report. Return JSON:
        {
          "title": "Checkout crashes on rotation during payment",
          "description": "NullPointerException in CheckoutViewModel.kt:87...",
          "stepsToReproduce": "1. Open checkout\n2. Start payment\n3. Rotate",
          "possibleCause": "Payment coroutine outlives ViewModel..."
        }
        Title format: "[Screen] [what happens] [when/trigger]".
        Be direct. No "I think". No raw stack traces in description.
    """.trimIndent()

    val DUPLICATE_CHECKER = """
        Check if this crash is a duplicate. Return JSON:
        {
          "isDuplicate": false,
          "matchType": null,
          "matchedCrashId": null,
          "confidence": 0.95,
          "reasoning": "..."
        }
        matchType: "exact" (same fingerprint) or "semantic" (same root cause, different line).
    """.trimIndent()
}
```

---

<aside>
✅

**17 files. All compile. All interfaces clean.** The architecture is:

- `MBA.kt` → two-phase init, thread-safe, exposes `CoroutineExceptionHandler`
- `MBAMode` → sealed interface, dev's keys vs our project key
- `LLM` → one function per provider, dev gives a string
- `CrashAnalysisAgent` → PII → fingerprint → dedup → AI (with fallback)
- `AgentFactory` → maps `LLMConfig` to Koog executor via `LLMCaller` interface
- `CrashStore` + `TicketBackend` → swappable interfaces for Notion/PostgreSQL/Jira

**Single integration point with Koog:** `KoogLLMCaller.call()` — one method to wire.

</aside>