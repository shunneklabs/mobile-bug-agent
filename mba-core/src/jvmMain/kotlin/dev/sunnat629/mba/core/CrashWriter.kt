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

internal actual object CrashWriter {

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
                manufacturer = System.getProperty("os.name") ?: "jvm",
                model = System.getProperty("os.arch") ?: "unknown",
                marketingName = null,
                osVersion = System.getProperty("os.version") ?: "unknown",
                sdkInt = -1,
                locale = java.util.Locale.getDefault().toString(),
                totalMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024),
                availableMemoryMb = Runtime.getRuntime().freeMemory() / (1024 * 1024),
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
                appVersion = metadata["jvm.app_version"] ?: "unknown",
                buildType = metadata["jvm.build_type"] ?: "unknown",
                currentScreen = currentScreen,
                breadcrumbs = breadcrumbs,
                customMetadata = metadata + mapOf(
                    "mba.coroutine_context" to (coroutineContext ?: "")
                )
            )

            val filename = "mba_crash_${report.id}_${report.timestamp.toString().replace(':', '-')}.json"
            File(dir, filename).writeText(json.encodeToString(report))
        }
    }
}
