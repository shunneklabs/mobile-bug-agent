package dev.sunnat629.mba.android.capture

import android.content.Context
import dev.sunnat629.mba.android.context.AndroidContextCollector
import dev.sunnat629.mba.core.MBA
import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.pii.PIISanitizer
import kotlinx.coroutines.*
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID
import kotlin.time.Clock

/**
 * Custom UncaughtExceptionHandler for capturing Android crashes.
 */
class MBACrashHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            // Android-specific metadata can be collected here
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val metadata = mapOf(
                "android.app_version" to (packageInfo.versionName ?: "unknown"),
                "android.build_type" to if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) "debug" else "release",
                "android.sdk_int" to android.os.Build.VERSION.SDK_INT.toString(),
                "android.device_model" to android.os.Build.MODEL
            )

            MBA.handleCrash(
                throwable = e,
                isFatal = true,
                threadName = t.name,
                metadata = metadata
            )
        } catch (ex: Exception) {
            MBALog.e("MBACrashHandler", "Error capturing Android crash: ${ex.message}", ex)
        } finally {
            // Forward to default handler
            defaultHandler?.uncaughtException(t, e)
        }
    }
}
