package dev.sunnat629.mba.android.context

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import dev.sunnat629.mba.core.model.DeviceContext

/**
 * Utility to collect Android-specific device context.
 */
object AndroidContextCollector {
    /**
     * Collects device information for the current application context.
     */
    fun collect(context: Context): DeviceContext {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memoryInfo)

        val config = context.resources.configuration
        val orientation = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            "landscape"
        } else {
            "portrait"
        }

        return DeviceContext(
            manufacturer = Build.MANUFACTURER ?: "Unknown",
            model = Build.MODEL ?: "Unknown",
            marketingName = null, // Can be refined with a library if needed
            osVersion = Build.VERSION.RELEASE ?: "Unknown",
            sdkInt = Build.VERSION.SDK_INT,
            locale = config.locales[0].toLanguageTag(),
            totalMemoryMb = memoryInfo.totalMem / (1024 * 1024),
            availableMemoryMb = memoryInfo.availMem / (1024 * 1024),
            isLowMemory = memoryInfo.lowMemory,
            screenDensity = context.resources.displayMetrics.density,
            orientation = orientation
        )
    }
}
