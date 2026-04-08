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
     * Installs the UncaughtExceptionHandler on the main thread for early crash capture.
     */
    fun install(context: Context) {
        if (isInstalled) return
        isInstalled = true

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        val mbaHandler = MBACrashHandler(context.applicationContext, defaultHandler)
        Thread.setDefaultUncaughtExceptionHandler(mbaHandler)

        // Initialize core SDK as well
        MBA.install()
    }
}
