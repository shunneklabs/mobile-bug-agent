package dev.sunnat629.mba.core

import dev.sunnat629.mba.core.config.MBAConfig
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName

/**
 * Public entry point for the MBA SDK.
 *
 * **Minimal external API** — external devs only interact with:
 * - [install] / [configure] / [init]
 * - [setScreen] / [addBreadcrumb] / [logError]
 * - [exceptionHandler] (attach to CoroutineScope)
 *
 * Two-phase initialization:
 *   1. [install] — sets crash handler (~2ms). Call early (Application.onCreate).
 *   2. [configure] — AI + backend config. Any thread. DI-friendly.
 *
 * Or use [init] for one-step convenience.
 *
 * Thread-safe. All mutable state is @Volatile or synchronized.
 */
public object MBA {

    @Volatile
    private var installed = false

    @Volatile
    private var configured = false

    private var config: MBAConfig? = null

    @Volatile
    public var currentScreen: String? = null
        internal set

    @get:JvmSynthetic // Hide from Java — internal use only
    internal lateinit var breadcrumbs: BreadcrumbTracker
        private set

    @get:JvmSynthetic
    internal lateinit var crashDirPath: String
        private set

    /**
     * [CoroutineExceptionHandler] that captures coroutine crashes.
     *
     * Usage:
     * ```kotlin
     * val scope = CoroutineScope(Dispatchers.IO + MBA.exceptionHandler)
     * ```
     */
    public val exceptionHandler: CoroutineExceptionHandler =
        CoroutineExceptionHandler { ctx, throwable ->
            val coroutineName = ctx[CoroutineName]?.name
            handleCrash(
                throwable = throwable,
                isFatal = false,
                threadName = "Coroutine",
                coroutineContext = coroutineName,
            )
        }

    // ------------------------------------------------------------------ //
    //  Public API — Phase 1: Install
    // ------------------------------------------------------------------ //

    /**
     * Phase 1: Install the crash handler.
     *
     * Call as early as possible (e.g., `Application.onCreate`).
     * Idempotent — second call is a no-op.
     *
     * @param crashDir Writable directory path for crash files.
     */
    public fun install(crashDir: String) {
        if (installed) return
        installed = true
        this.crashDirPath = crashDir
        this.breadcrumbs = BreadcrumbTracker()

        PlatformInitializer.installCrashHandler { threadName, throwable ->
            handleCrash(
                throwable = throwable,
                isFatal = true,
                threadName = threadName,
            )
        }
    }

    // ------------------------------------------------------------------ //
    //  Public API — Phase 2: Configure
    // ------------------------------------------------------------------ //

    /**
     * Phase 2: Configure AI processing and ticket backends.
     *
     * Requires [install] to be called first.
     * Idempotent — second call is a no-op.
     */
    public fun configure(config: MBAConfig) {
        check(installed) { "Call MBA.install(crashDir) before MBA.configure()" }
        if (configured) return
        configured = true
        this.config = config
    }

    /**
     * Convenience: [install] + [configure] in one call.
     *
     * ```kotlin
     * MBA.init(crashDir = context.filesDir.path + "/crashes") {
     *     mode = MBAMode.SdkOnly(llmApiKey = "...", ticketBackend = notionBackend)
     * }
     * ```
     */
    public fun init(crashDir: String, block: MBAConfig.Builder.() -> Unit) {
        install(crashDir)
        configure(MBAConfig.Builder().apply(block).build())
    }

    // ------------------------------------------------------------------ //
    //  Public API — Runtime
    // ------------------------------------------------------------------ //

    /** Set current screen name for crash context. */
    public fun setScreen(name: String) {
        currentScreen = name
    }

    /** Add a breadcrumb for crash context. Thread-safe. */
    public fun addBreadcrumb(message: String) {
        if (installed) breadcrumbs.add(message)
    }

    /**
     * Log a non-fatal error. Queued for background processing.
     *
     * @param throwable The error to log.
     * @param metadata Optional key-value pairs for extra context.
     */
    public fun logError(
        throwable: Throwable,
        metadata: Map<String, String> = emptyMap(),
    ) {
        if (!installed) return
        handleCrash(
            throwable = throwable,
            isFatal = false,
            threadName = Thread.currentThread().name,
            metadata = metadata,
        )
    }

    // ------------------------------------------------------------------ //
    //  Internal — accessed by mba-android / mba-jvm modules
    // ------------------------------------------------------------------ //

    /** Get current config. Throws if not configured. */
    @JvmSynthetic
    internal fun requireConfig(): MBAConfig =
        config ?: error("MBA not configured. Call MBA.configure() first.")

    /** Check if configured (for optional features that degrade gracefully). */
    @JvmSynthetic
    internal fun isConfigured(): Boolean = configured

    /**
     * Core crash handler. Writes crash data to disk synchronously.
     *
     * MUST NOT throw — called from UncaughtExceptionHandler.
     */
    @JvmSynthetic
    internal fun handleCrash(
        throwable: Throwable,
        isFatal: Boolean,
        threadName: String,
        coroutineContext: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ) {
        try {
            CrashWriter.writeToDisk(
                crashDir = crashDirPath,
                throwable = throwable,
                isFatal = isFatal,
                threadName = threadName,
                coroutineContext = coroutineContext,
                currentScreen = currentScreen,
                breadcrumbs = breadcrumbs.snapshot(),
                metadata = metadata,
            )
        } catch (e: Throwable) {
            // Best-effort: log to stderr so disk-write failures aren't invisible.
            try {
                System.err.println("MBA: Failed to write crash to disk: ${e.message}")
            } catch (_: Throwable) {
                // Truly nothing we can do.
            }
        }
    }
}

/**
 * Platform-specific crash handler installer.
 * Implemented in androidMain / jvmMain.
 */
internal expect object PlatformInitializer {
    fun installCrashHandler(onCrash: (String, Throwable) -> Unit)
}
