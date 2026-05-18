package dev.sunnat629.mba.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.RawCrashReport
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object ANRExitReporter {
    private const val TAG = "ANR"
    private const val MAX_EXIT_REASONS = 20

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    fun capturePreviousAnrIfAny(
        context: Context,
        crashDir: String,
        metadata: Map<String, String>,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            MBALog.d(TAG, "ANR exit detection requires Android 11/API 30+")
            return
        }

        runCatching {
            capturePreviousAnrApi30(context, crashDir, metadata)
        }.onFailure { error ->
            MBALog.e(TAG, "Failed to inspect previous ANR exit", error)
        }
    }

    private fun capturePreviousAnrApi30(
        context: Context,
        crashDir: String,
        metadata: Map<String, String>,
    ) {
        val activityManager = context.getSystemService(ActivityManager::class.java)
            ?: return
        val lastTimestamp = MBAPreferences.loadLastAnrTimestamp(context)
        val previousAnr = activityManager
            .getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_REASONS)
            .asSequence()
            .filter { exit -> exit.reason == ApplicationExitInfo.REASON_ANR }
            .filter { exit -> exit.timestamp > lastTimestamp }
            .maxByOrNull { exit -> exit.timestamp }
            ?: return

        val report = previousAnr.toRawCrashReport(metadata)
        val dir = File(crashDir)
        dir.mkdirs()
        val file = File(dir, "mba_crash_${report.id}_${report.timestamp.toString().replace(':', '-')}.json")
        file.writeText(json.encodeToString(report))
        MBAPreferences.saveLastAnrTimestamp(context, previousAnr.timestamp)
        MBALog.i(TAG, "Captured previous ANR exit as pending crash: ${file.name}")
    }

    private fun ApplicationExitInfo.toRawCrashReport(metadata: Map<String, String>): RawCrashReport {
        val trace = traceText()
        return RawCrashReport(
            id = UUID.randomUUID().toString(),
            timestamp = Instant.fromEpochMilliseconds(timestamp),
            exceptionType = "android.app.ApplicationExitInfo.REASON_ANR",
            message = description?.takeIf { it.isNotBlank() } ?: "Application Not Responding",
            stackTrace = trace ?: fallbackTrace(),
            threadName = "main",
            isFatal = true,
            device = DeviceContext(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                marketingName = null,
                osVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
                sdkInt = Build.VERSION.SDK_INT,
                locale = Locale.getDefault().toString(),
                totalMemoryMb = -1,
                availableMemoryMb = -1,
                isLowMemory = false,
                screenDensity = -1f,
                orientation = "unknown",
            ),
            appVersion = metadata["android.app_version"] ?: "unknown",
            buildType = metadata["android.build_type"] ?: "unknown",
            currentScreen = null,
            breadcrumbs = emptyList(),
            customMetadata = metadata + mapOf(
                "android.exit_reason" to "ANR",
                "android.exit_timestamp" to timestamp.toString(),
                "android.exit_process" to processName.orEmpty(),
                "android.exit_status" to status.toString(),
                "android.exit_importance" to importance.toString(),
            ),
        )
    }

    private fun ApplicationExitInfo.traceText(): String? =
        runCatching {
            traceInputStream?.bufferedReader()?.use { reader ->
                reader.readText().takeIf { it.isNotBlank() }
            }
        }.getOrNull()

    private fun ApplicationExitInfo.fallbackTrace(): String = buildString {
        appendLine("Application Not Responding")
        appendLine("Process: ${processName.orEmpty()}")
        appendLine("Reason: ApplicationExitInfo.REASON_ANR")
        description?.takeIf { it.isNotBlank() }?.let { appendLine("Description: $it") }
        appendLine("Timestamp: $timestamp")
    }
}
