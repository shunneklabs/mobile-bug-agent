package dev.sunnat629.mba.core

import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.RawCrashReport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID
import kotlin.time.Clock

public actual object CrashWriter {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

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
        runCatching {
            val dir = File(crashDir)
            dir.mkdirs()

            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stack = sw.toString()

            val device = DeviceContext(
                manufacturer = android.os.Build.MANUFACTURER,
                model = android.os.Build.MODEL,
                marketingName = null,
                osVersion = android.os.Build.VERSION.RELEASE ?: android.os.Build.VERSION.SDK_INT.toString(),
                sdkInt = android.os.Build.VERSION.SDK_INT,
                locale = java.util.Locale.getDefault().toString(),
                totalMemoryMb = -1,
                availableMemoryMb = -1,
                isLowMemory = false,
                screenDensity = -1f,
                orientation = "unknown",
            )

            val report = RawCrashReport(
                id = UUID.randomUUID().toString(),
                timestamp = Clock.System.now(),
                exceptionType = throwable::class.qualifiedName ?: throwable::class.simpleName ?: "Throwable",
                message = throwable.message,
                stackTrace = stack,
                threadName = threadName,
                isFatal = isFatal,
                device = device,
                appVersion = metadata["android.app_version"] ?: "unknown",
                buildType = metadata["android.build_type"] ?: "unknown",
                currentScreen = currentScreen,
                breadcrumbs = breadcrumbs,
                customMetadata = metadata + mapOf(
                    "mba.coroutine_context" to (coroutineContext ?: "")
                )
            )

            val filename = "mba_crash_${report.id}_${report.timestamp.toString().replace(':', '-')}.json"
            val file = File(dir, filename)
            file.writeText(json.encodeToString(report))
        }
    }
}
