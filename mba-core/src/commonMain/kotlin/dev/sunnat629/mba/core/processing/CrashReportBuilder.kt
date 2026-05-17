package dev.sunnat629.mba.core.processing

import dev.sunnat629.mba.core.fingerprint.CrashFingerprint
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.model.Severity
import dev.sunnat629.mba.core.pii.PIISanitizer

/**
 * Builds a fallback [ProcessedCrashReport] without LLM analysis.
 *
 * This is shared by Android, JVM, iOS, and server adapters for cases where the
 * Koog agent is unavailable but the SDK still needs a structured report.
 */
public object CrashReportBuilder {

    private val piiSanitizer = PIISanitizer()

    public fun build(raw: RawCrashReport): ProcessedCrashReport {
        val sanitizedTrace = piiSanitizer.scrub(raw.stackTrace)
        val sanitizedMessage = raw.message?.let { piiSanitizer.scrub(it) }

        val fingerprint = CrashFingerprint.compute(
            exceptionType = raw.exceptionType,
            stackTrace = sanitizedTrace,
        )

        val firstAppFrame = sanitizedTrace.lines()
            .firstOrNull {
                val frame = it.trim()
                frame.startsWith("at ") &&
                    !frame.contains("android.") &&
                    !frame.contains("java.") &&
                    !frame.contains("kotlin.")
            }
            ?.trim()
        val crashFile = firstAppFrame
            ?.substringAfter("(", "")
            ?.substringBefore(":", "")
            ?.ifBlank { null }
        val crashLine = firstAppFrame
            ?.substringAfter(":", "")
            ?.substringBefore(")", "")
            ?.toIntOrNull()
        val crashMethod = firstAppFrame
            ?.substringAfter("at ", "")
            ?.substringBefore("(", "")
            ?.ifBlank { null }

        val shortType = raw.exceptionType.substringAfterLast(".")
        val screen = raw.currentScreen ?: "unknown screen"
        val title = "$shortType in $screen"

        val description = buildString {
            append("$shortType: ${sanitizedMessage ?: "(no message)"}")
            appendLine()
            if (raw.isFatal) append("Fatal crash on thread '${raw.threadName}'.")
            else append("Non-fatal error on thread '${raw.threadName}'.")
            append(" App ${raw.appVersion} (${raw.buildType}).")
            crashMethod?.let {
                appendLine()
                append("Location: $it")
                crashFile?.let { file ->
                    append(" ($file${crashLine?.let { line -> ":$line" } ?: ""})")
                }
            }
        }

        return ProcessedCrashReport(
            raw = raw.copy(
                stackTrace = sanitizedTrace,
                message = sanitizedMessage,
            ),
            fingerprint = fingerprint,
            severity = Severity.MEDIUM,
            confidence = 1.0f,
            title = title,
            description = description,
            possibleCause = buildPossibleCause(raw, crashFile, crashLine, crashMethod),
            stepsToReproduce = buildStepsToReproduce(raw),
            crashFile = crashFile,
            crashLine = crashLine,
            crashMethod = crashMethod,
            isAppCode = firstAppFrame != null,
            sanitizedStackTrace = sanitizedTrace,
        )
    }

    private fun buildStepsToReproduce(raw: RawCrashReport): String =
        when {
            raw.breadcrumbs.isNotEmpty() -> raw.breadcrumbs
                .mapIndexed { index, breadcrumb -> "${index + 1}. $breadcrumb" }
                .joinToString("\n")
            raw.currentScreen != null -> "1. Open ${raw.currentScreen}\n2. Repeat the action that triggered ${raw.exceptionType.substringAfterLast(".")}"
            else -> "1. Launch app version ${raw.appVersion}\n2. Repeat the user action immediately before the crash"
        }

    private fun buildPossibleCause(
        raw: RawCrashReport,
        crashFile: String?,
        crashLine: Int?,
        crashMethod: String?,
    ): String = buildString {
        append(raw.exceptionType.substringAfterLast("."))
        crashMethod?.let { append(" in $it") }
        crashFile?.let { append(" ($it${crashLine?.let { line -> ":$line" } ?: ""})") }
        raw.message?.let { append(": $it") }
    }
}
