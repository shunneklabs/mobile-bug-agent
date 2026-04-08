package dev.sunnat629.mba.core

import dev.sunnat629.mba.core.config.MBAConfig
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName

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

    @Volatile
    private var installed = false
    @Volatile
    private var configured = false
    private var config: MBAConfig? = null

    @Volatile
    var currentScreen: String? = null
        internal set

    internal lateinit var breadcrumbs: BreadcrumbTracker
        private set

    internal lateinit var crashDirPath: String
        private set

    /**
     * CoroutineExceptionHandler — add to your scope to capture coroutine crashes.
     */
    val exceptionHandler = CoroutineExceptionHandler { ctx, throwable ->
        val coroutineName = ctx[CoroutineName]?.name
        handleCrash(
            throwable = throwable,
            isFatal = false,
            threadName = "Coroutine",
            coroutineContext = coroutineName,
        )
    }

    /**
     * Phase 1: Install crash handler.
     */
    fun install(crashDir: String) {
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

    /**
     * Phase 2: Configure AI processing + backends.
     */
    fun configure(config: MBAConfig) {
        check(installed) { "Call MBA.install(crashDir) before MBA.configure()" }
        if (configured) return
        configured = true
        this.config = config
    }

    /**
     * Convenience: install + configure in one call.
     */
    fun init(crashDir: String, block: MBAConfig.Builder.() -> Unit) {
        install(crashDir)
        configure(MBAConfig.Builder().apply(block).build())
    }

    /** Set current screen name for crash context. */
    fun setScreen(name: String) {
        currentScreen = name
    }

    /** Add a breadcrumb for crash context. Thread-safe. */
    fun addBreadcrumb(message: String) {
        if (installed) breadcrumbs.add(message)
    }

    /** Log a non-fatal error. Queued for background processing. */
    fun logError(throwable: Throwable, threadName: String = "Unknown", metadata: Map<String, String> = emptyMap()) {
        if (installed) {
            handleCrash(
                throwable = throwable,
                isFatal = false,
                threadName = threadName,
                metadata = metadata,
            )
        }
    }

    /** Get current config. Throws if not configured. */
    internal fun requireConfig(): MBAConfig =
        config ?: error("MBA not configured. Call MBA.configure() first.")

    /** Check if configured (for optional features that degrade gracefully). */
    internal fun isConfigured(): Boolean = configured

    // ---- Internal crash handling ----

    fun handleCrash(
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
        } catch (_: Throwable) {
        }
    }
}

internal expect object PlatformInitializer {
    fun installCrashHandler(onCrash: (String, Throwable) -> Unit)
}

/**
 * Internal interface for the disk crash writer.
 */
internal expect object CrashWriter {
    fun writeToDisk(
        crashDir: String,
        throwable: Throwable,
        isFatal: Boolean,
        threadName: String,
        coroutineContext: String?,
        currentScreen: String?,
        breadcrumbs: List<String>,
        metadata: Map<String, String>,
    )
}
