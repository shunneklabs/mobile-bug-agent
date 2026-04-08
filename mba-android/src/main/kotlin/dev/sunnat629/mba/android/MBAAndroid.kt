package dev.sunnat629.mba.android

import android.content.Context
import dev.sunnat629.mba.android.capture.MBACrashHandler
import dev.sunnat629.mba.core.MBA

/**
 * Android-specific entry point for initializing the SDK capture logic.
 */
object MBAAndroid {
    private var isInstalled = false

    /**
     * Installs the UncaughtExceptionHandler on the process.
     *
     * Uses context.filesDir/mba-crashes as the default crash directory.
     */
    fun install(context: Context) {
        if (isInstalled) return
        isInstalled = true

        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        val mbaHandler = MBACrashHandler(appContext, defaultHandler)
        Thread.setDefaultUncaughtExceptionHandler(mbaHandler)

        val crashDir = appContext.filesDir.resolve("mba-crashes").absolutePath
        MBA.install(crashDir)

        // Best-effort: process crashes from previous run.
        runCatching { PendingCrashProcessor.process(appContext) }
    }
}
