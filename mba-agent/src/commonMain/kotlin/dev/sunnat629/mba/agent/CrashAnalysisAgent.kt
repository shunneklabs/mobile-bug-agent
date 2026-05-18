package dev.sunnat629.mba.agent

import dev.sunnat629.mba.agent.model.CrashSummary
import dev.sunnat629.mba.agent.model.ParsedStackTrace
import dev.sunnat629.mba.agent.model.SeverityResult
import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.fingerprint.CrashFingerprint
import dev.sunnat629.mba.core.model.*
import dev.sunnat629.mba.core.pii.PIISanitizer
import dev.sunnat629.mba.core.store.LocalDedupCache

class CrashAnalysisAgent(
    private val agentFactory: AgentFactory,
    private val piiSanitizer: PIISanitizer,
    private val dedupCache: LocalDedupCache,
    private val useLocalDedup: Boolean = true,
) {
    private companion object {
        const val TAG = "Agent"
    }

    suspend fun process(raw: RawCrashReport): CrashAnalysisResult {
        MBALog.i(TAG, "Processing crash: id=${raw.id}, type=${raw.exceptionType}, fatal=${raw.isFatal}")

        // 1. PII scrub
        MBALog.d(TAG, "[1/5] PII scrub...")
        val sanitizedTrace = piiSanitizer.scrub(raw.stackTrace)
        val sanitizedMessage = raw.message?.let { piiSanitizer.scrub(it) }
        val sanitizedBreadcrumbs = raw.breadcrumbs.map { piiSanitizer.scrub(it) }

        // 2. Fingerprint
        MBALog.d(TAG, "[2/5] Computing fingerprint...")
        val fingerprint = CrashFingerprint.compute(
            exceptionType = raw.exceptionType,
            stackTrace = sanitizedTrace,
        )

        // 3. Local dedup
        MBALog.d(TAG, "[3/5] Dedup check for ${fingerprint.take(12)}...")
        if (useLocalDedup && dedupCache.contains(fingerprint)) {
            dedupCache.touch(fingerprint)
            MBALog.w(TAG, "\u2b50 Duplicate detected: ${fingerprint.take(12)}... \u2014 skipping LLM")
            return CrashAnalysisResult.Duplicate(
                DuplicateCrashReport(
                    fingerprint = fingerprint,
                    newDevice = raw.device,
                    timestamp = raw.timestamp,
                )
            )
        }

        // 4. AI analysis
        return try {
            MBALog.d(TAG, "[4/5] Running AI analysis...")
            val executor = agentFactory.create()

            val parsed: ParsedStackTrace = executor.parseStackTrace(sanitizedTrace)
            MBALog.d(TAG, "  Parsed: file=${parsed.crashFile}, line=${parsed.crashLine}, method=${parsed.crashMethod}")

            val severity: SeverityResult = executor.classifySeverity(parsed, raw.device)
            MBALog.d(TAG, "  Severity: ${severity.severity} (confidence=${severity.confidence})")

            val summary: CrashSummary = executor.generateSummary(
                parsed = parsed,
                severity = severity,
                screen = raw.currentScreen,
                breadcrumbs = sanitizedBreadcrumbs,
                device = raw.device,
                crashContext = raw.summaryContext(),
            )
            MBALog.d(TAG, "  Summary: '${summary.title}'")

            // 5. Cache
            if (useLocalDedup) dedupCache.put(fingerprint)
            MBALog.i(TAG, "\u2705 New crash processed: '${summary.title}' [${severity.severity}]")

            CrashAnalysisResult.New(
                ProcessedCrashReport(
                    raw = raw.copy(
                        stackTrace = sanitizedTrace,
                        message = sanitizedMessage,
                        breadcrumbs = sanitizedBreadcrumbs,
                    ),
                    fingerprint = fingerprint,
                    severity = severity.severity,
                    confidence = severity.confidence,
                    title = summary.title,
                    description = summary.description,
                    stepsToReproduce = summary.stepsToReproduce?.takeIf { it.isNotBlank() }
                        ?: fallbackSteps(raw, sanitizedBreadcrumbs),
                    possibleCause = summary.possibleCause?.takeIf { it.isNotBlank() }
                        ?: fallbackPossibleCause(parsed, raw),
                    crashFile = parsed.crashFile,
                    crashLine = parsed.crashLine,
                    crashMethod = parsed.crashMethod,
                    isAppCode = parsed.isAppCode,
                    sanitizedStackTrace = sanitizedTrace,
                )
            )
        } catch (e: Exception) {
            MBALog.e(TAG, "\u274c AI analysis failed, using fallback", e)
            if (useLocalDedup) dedupCache.put(fingerprint)
            CrashAnalysisResult.Fallback(
                report = ProcessedCrashReport(
                    raw = raw,
                    fingerprint = fingerprint,
                    severity = Severity.MEDIUM,
                    confidence = 0.0f,
                    title = "${raw.exceptionType} in ${raw.currentScreen ?: "unknown"}",
                    description = "AI processing failed: ${e.message}. Raw stack trace attached.",
                    sanitizedStackTrace = sanitizedTrace,
                ),
                error = e,
            )
        }
    }

    private fun RawCrashReport.summaryContext(): String = buildString {
        appendLine("Crash metadata:")
        appendLine("- Occurred At: $timestamp")
        appendLine("- Exception: $exceptionType")
        message?.let { appendLine("- Exception Message: $it") }
        appendLine("- Thread: $threadName")
        appendLine("- App Version: $appVersion")
        appendLine("- Build Type: $buildType")
        appendLine("- Device Model: ${device.displayName}")
        appendLine("- OS Version: Android ${device.osVersion} (API ${device.sdkInt})")
        currentScreen?.let { appendLine("- Current Screen: $it") }
        if (customMetadata.isNotEmpty()) {
            appendLine("- Custom Metadata: ${customMetadata.entries.joinToString { "${it.key}=${it.value}" }}")
        }
    }

    private fun fallbackSteps(raw: RawCrashReport, breadcrumbs: List<String>): String =
        when {
            breadcrumbs.isNotEmpty() -> breadcrumbs.mapIndexed { index, breadcrumb -> "${index + 1}. $breadcrumb" }.joinToString("\n")
            raw.currentScreen != null -> "1. Open ${raw.currentScreen}\n2. Repeat the action that triggered ${raw.exceptionType.substringAfterLast(".")}"
            else -> "1. Launch app version ${raw.appVersion}\n2. Repeat the user action immediately before the crash"
        }

    private fun fallbackPossibleCause(parsed: ParsedStackTrace, raw: RawCrashReport): String =
        buildString {
            append(raw.exceptionType.substringAfterLast("."))
            parsed.crashMethod?.let { append(" in $it") }
            parsed.crashFile?.let { append(" (${it}${parsed.crashLine?.let { line -> ":$line" } ?: ""})") }
            raw.message?.let { append(": $it") }
        }
}

sealed class CrashAnalysisResult {
    data class New(val report: ProcessedCrashReport) : CrashAnalysisResult()
    data class Duplicate(val report: DuplicateCrashReport) : CrashAnalysisResult()
    data class Fallback(val report: ProcessedCrashReport, val error: Exception) : CrashAnalysisResult()
}
