package dev.sunnat629.mba.core

/**
 * Platform-specific crash persistence.
 *
 * Contract:
 * - Implementations MUST be best-effort and MUST NOT throw.
 * - MUST be safe to call from an UncaughtExceptionHandler (no allocations if possible).
 * - Writes raw crash data to disk; AI processing happens later (WorkManager / background).
 *
 * Implemented in:
 * - androidMain → DiskCrashWriter (writes JSON to app-internal files dir)
 * - jvmMain → JVMCrashHandler (writes to temp dir)
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
