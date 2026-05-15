package dev.sunnat629.mba.core

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity as KermitSeverity

/**
 * Centralized internal logger for the MBA SDK.
 *
 * Wraps [Kermit](https://github.com/touchlab/Kermit) — a true KMP logging library
 * that uses platform-native output:
 * - **Android**: `android.util.Log` (shows in Logcat with proper tags)
 * - **iOS**: `NSLog` / `os_log`
 * - **JVM**: `println` to stdout
 *
 * **Gated by `MBAConfig.debug`** — when debug=false, all log calls
 * are no-ops with zero overhead (single boolean check).
 *
 * All SDK modules use `MBALog` instead of direct Log/println calls.
 * External devs never see or call this.
 *
 * Tag convention: `MBA/<component>` (e.g. `MBA/Agent`, `MBA/Notion`)
 */
public object MBALog {

    private const val PREFIX = "MBA"

    @Volatile
    public var enabled: Boolean = false

    private fun tag(component: String) = "$PREFIX/$component"

    // ──────────────────────────────────────────────────────────────── //
    //  Log levels
    // ──────────────────────────────────────────────────────────────── //

    /** Verbose internal state, pipeline steps, timing. */
    public fun d(component: String, message: String) {
        if (!enabled) return
        Logger.d(tag(component)) { message }
    }

    /** Key lifecycle events: install, configure, ticket created. */
    public fun i(component: String, message: String) {
        if (!enabled) return
        Logger.i(tag(component)) { message }
    }

    /** Recoverable issues: dedup hit, fallback triggered. */
    public fun w(component: String, message: String) {
        if (!enabled) return
        Logger.w(tag(component)) { message }
    }

    /** Failures: LLM error, disk write failure, ticket creation failed. */
    public fun e(component: String, message: String, throwable: Throwable? = null) {
        if (!enabled) return
        if (throwable != null) {
            Logger.e(throwable, tag(component)) { message }
        } else {
            Logger.e(tag(component)) { message }
        }
    }
}
