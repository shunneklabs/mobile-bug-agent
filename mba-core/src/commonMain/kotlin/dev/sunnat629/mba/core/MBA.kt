package dev.sunnat629.mba.core

import dev.sunnat629.mba.core.config.MBAConfig
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName

public object MBA {

    private const val TAG = "Core"

    @Volatile
    private var installed = false

    @Volatile
    private var configured = false

    private var config: MBAConfig? = null

    @Volatile
    private var globalMetadata: Map<String, String> = emptyMap()

    @Volatile
    public var currentScreen: String? = null
        internal set

    @get:JvmSynthetic
    internal lateinit var breadcrumbs: BreadcrumbTracker
        private set

    @get:JvmSynthetic
    internal lateinit var crashDirPath: String
        private set

    public val exceptionHandler: CoroutineExceptionHandler =
        CoroutineExceptionHandler { ctx, throwable ->
            val name = ctx[CoroutineName]?.name ?: "unnamed"
            MBALog.w(TAG, "Coroutine exception: ${throwable::class.simpleName} in '$name'")
            handleCrash(throwable, isFatal = false, threadName = "Coroutine", coroutineContext = name)
        }

    // ------------------------------------------------------------------ //
    //  Phase 1: Install
    // ------------------------------------------------------------------ //

    public fun install(crashDir: String) {
        if (installed) {
            MBALog.d(TAG, "install() called again — skipping (already installed)")
            return
        }
        installed = true
        crashDirPath = crashDir
        breadcrumbs = BreadcrumbTracker()

        MBALog.i(TAG, "✔ Installing crash handler. dir=$crashDir")

        PlatformInitializer.installCrashHandler { threadName, throwable ->
            MBALog.e(TAG, "💥 FATAL crash on '$threadName': ${throwable::class.simpleName}: ${throwable.message}")
            handleCrash(throwable, isFatal = true, threadName = threadName)
        }

        MBALog.i(TAG, "✔ Crash handler installed")
    }

    // ------------------------------------------------------------------ //
    //  Phase 2: Configure
    // ------------------------------------------------------------------ //

    public fun configure(config: MBAConfig) {
        check(installed) { "Call MBA.install(crashDir) before MBA.configure()" }
        if (configured) {
            MBALog.d(TAG, "configure() called again — skipping (already configured)")
            return
        }
        configured = true
        this.config = config

        // Enable logging AFTER config so the debug flag takes effect immediately
        MBALog.enabled = config.debug

        MBALog.i(TAG, "✔ Configured. debug=${config.debug}, mode=${config.mode::class.simpleName}, llm=${config.llm}")
    }

    public fun init(crashDir: String, block: MBAConfig.Builder.() -> Unit) {
        install(crashDir)
        configure(MBAConfig.Builder().apply(block).build())
    }

    // ------------------------------------------------------------------ //
    //  Runtime
    // ------------------------------------------------------------------ //

    public fun setScreen(name: String) {
        MBALog.d(TAG, "Screen → '$name'")
        currentScreen = name
    }

    public fun addBreadcrumb(message: String) {
        if (!installed) return
        MBALog.d(TAG, "Breadcrumb: '$message'")
        breadcrumbs.add(message)
    }

    public fun setGlobalMetadata(metadata: Map<String, String>) {
        globalMetadata = metadata
    }

    public fun logError(
        throwable: Throwable,
        metadata: Map<String, String> = emptyMap(),
    ) {
        if (!installed) return
        MBALog.w(TAG, "Non-fatal error logged: ${throwable::class.simpleName}: ${throwable.message}" +
            if (metadata.isNotEmpty()) " metadata=$metadata" else "")
        handleCrash(throwable, isFatal = false, threadName = Thread.currentThread().name, metadata = metadata)
    }

    // ------------------------------------------------------------------ //
    //  Internal
    // ------------------------------------------------------------------ //

    @JvmSynthetic
    internal fun requireConfig(): MBAConfig =
        config ?: error("MBA not configured. Call MBA.configure() first.")

    @JvmSynthetic
    internal fun isConfigured(): Boolean = configured

    @JvmSynthetic
    public fun handleCrash(
        throwable: Throwable,
        isFatal: Boolean,
        threadName: String,
        coroutineContext: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ) {
        MBALog.d(TAG, "handleCrash: fatal=$isFatal, thread=$threadName, type=${throwable::class.simpleName}")
        try {
            val mergedMetadata = globalMetadata + metadata
            CrashWriter.writeToDisk(
                crashDir = crashDirPath,
                throwable = throwable,
                isFatal = isFatal,
                threadName = threadName,
                coroutineContext = coroutineContext,
                currentScreen = currentScreen,
                breadcrumbs = breadcrumbs.snapshot(),
                metadata = mergedMetadata,
            )
            MBALog.d(TAG, "Crash written to disk successfully")
        } catch (e: Throwable) {
            MBALog.e(TAG, "Failed to write crash to disk", e)
            try { System.err.println("MBA: disk write failed: ${e.message}") } catch (_: Throwable) {}
        }
    }
}

public expect object PlatformInitializer {
    fun installCrashHandler(onCrash: (String, Throwable) -> Unit)
}
