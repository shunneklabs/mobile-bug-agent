package dev.sunnat629.mba.core

import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.model.DeviceContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.time.Clock

internal actual object CrashWriter {
    actual fun writeToDisk(
        crashDir: String,
        throwable: Throwable,
        isFatal: Boolean,
        threadName: String,
        coroutineContext: String?,
        currentScreen: String?,
        breadcrumbs: List<String>,
        metadata: Map<String, String>,
    ) {
        val report = RawCrashReport(
            id = UUID.randomUUID().toString(),
            timestamp = Clock.System.now(),
            exceptionType = throwable::class.simpleName ?: "Unknown",
            message = throwable.message,
            stackTrace = throwable.stackTraceToString(),
            threadName = threadName,
            isFatal = isFatal,
            device = DeviceContext(
                manufacturer = android.os.Build.MANUFACTURER,
                model = android.os.Build.MODEL,
                osVersion = android.os.Build.VERSION.RELEASE,
                sdkInt = android.os.Build.VERSION.SDK_INT,
                locale = java.util.Locale.getDefault().toString(),
                totalMemoryMb = 0, // Should be populated if possible
                availableMemoryMb = 0
            ),
            appVersion = "unknown", // To be filled by platform handler if possible
            buildType = "unknown",
            currentScreen = currentScreen,
            breadcrumbs = breadcrumbs,
            customMetadata = metadata + (coroutineContext?.let { mapOf("coroutine" to it) } ?: emptyMap())
        )

        val json = Json.encodeToString(report)
        val dir = File(crashDir)
        if (!dir.exists()) dir.mkdirs()
        
        val filename = "crash_${report.timestamp.toEpochMilliseconds()}_${report.id.take(8)}.json"
        val file = File(dir, filename)
        file.writeText(json)
    }
}
