package dev.sunnat629.mba.android

import android.content.Context

/**
 * Reads and processes crash files written during previous sessions.
 *
 * MVP scaffold:
 * - Listing + deletion is implemented in this module.
 * - Actual processing (agent + upload) will be wired next.
 */
internal object PendingCrashProcessor {

    fun process(context: Context) {
        // Intentionally minimal for the initial commit.
        // TODO: read crashDir, parse RawCrashReport JSON, run agent pipeline, enqueue uploads.
    }
}
