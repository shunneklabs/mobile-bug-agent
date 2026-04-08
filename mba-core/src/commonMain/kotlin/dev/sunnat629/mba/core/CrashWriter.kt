package dev.sunnat629.mba.core

/**
 * Platform-specific crash persistence.
 *
 * - Implementations must be best-effort and must not throw.
 * - Must be safe to call from an UncaughtExceptionHandler.
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
