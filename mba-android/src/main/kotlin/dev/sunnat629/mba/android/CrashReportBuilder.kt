package dev.sunnat629.mba.android

import dev.sunnat629.mba.core.fingerprint.CrashFingerprint
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.model.Severity
import dev.sunnat629.mba.core.pii.PIISanitizer

/**
 * Builds a [ProcessedCrashReport] from a [RawCrashReport] WITHOUT AI.
 *
 * Uses:
 * - PII sanitizer to scrub stack traces
 * - CrashFingerprint for dedup
 * - Basic template for title/description (no LLM needed)
 * - Default severity: MEDIUM
 *
 * AI enrichment can be layered on top later.
 */
internal object CrashReportBuilder {

    private val piiSanitizer = PIISanitizer()

    fun build(raw: RawCrashReport): ProcessedCrashReport {
        // PII scrub
        val sanitizedTrace = piiSanitizer.scrub(raw.stackTrace)
        val sanitizedMessage = raw.message?.let { piiSanitizer.scrub(it) }

        // Fingerprint
        val fingerprint = CrashFingerprint.compute(
            exceptionType = raw.exceptionType,
            stackTrace = sanitizedTrace,
        )

        // Extract crash location from stack trace
        val firstAppFrame = sanitizedTrace.lines()
            .firstOrNull { it.trim().startsWith("at ") && !it.contains("android.") && !it.contains("java.") && !it.contains("kotlin.") }
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

        // Short exception name
        val shortType = raw.exceptionType.substringAfterLast(".")

        // Basic title: "NullPointerException in CheckoutScreen"
        val screen = raw.currentScreen ?: "unknown screen"
        val title = "$shortType in $screen"

        // Basic description
        val description = buildString {
            append("$shortType: ${sanitizedMessage ?: "(no message)"}")
            appendLine()
            if (raw.isFatal) append("Fatal crash on thread '${raw.threadName}'.")
            else append("Non-fatal error on thread '${raw.threadName}'.")
            append(" App ${raw.appVersion} (${raw.buildType}).")
            crashMethod?.let {
                appendLine()
                append("Location: $it")
                crashFile?.let { f -> append(" ($f${crashLine?.let { l -> ":$l" } ?: ""})" ) }
            }
        }

        return ProcessedCrashReport(
            raw = raw.copy(
                stackTrace = sanitizedTrace,
                message = sanitizedMessage,
            ),
            fingerprint = fingerprint,
            severity = Severity.MEDIUM,
            confidence = 1.0f, // No AI uncertainty — this is raw data
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
