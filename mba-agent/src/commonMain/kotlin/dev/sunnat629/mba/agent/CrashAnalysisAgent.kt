package dev.sunnat629.mba.agent

import dev.sunnat629.mba.agent.model.CrashSummary
import dev.sunnat629.mba.agent.model.ParsedStackTrace
import dev.sunnat629.mba.agent.model.SeverityResult
import dev.sunnat629.mba.core.fingerprint.CrashFingerprint
import dev.sunnat629.mba.core.model.*
import dev.sunnat629.mba.core.pii.PIISanitizer
import dev.sunnat629.mba.core.store.LocalDedupCache

/**
 * The core crash analysis pipeline.
 *
 * Orchestrates:
 * 1. PII scrub (regex, no LLM, ~1-3ms)
 * 2. Fingerprint (SHA-256, no LLM, <1ms)
 * 3. Local dedup check (cache, no LLM, <1ms)
 * 4. AI analysis (LLM calls, 2-8s)
 * 5. Result packaging
 *
 * **Internal** — runs on background thread (WorkManager). Never on main thread.
 */
internal class CrashAnalysisAgent(
    private val agentFactory: AgentFactory,
    private val piiSanitizer: PIISanitizer,
    private val dedupCache: LocalDedupCache,
) {
    suspend fun process(raw: RawCrashReport): CrashAnalysisResult {
        // 1. PII scrub — fast, no network
        val sanitizedTrace = piiSanitizer.scrub(raw.stackTrace)
        val sanitizedMessage = raw.message?.let { piiSanitizer.scrub(it) }
        val sanitizedBreadcrumbs = raw.breadcrumbs.map { piiSanitizer.scrub(it) }

        // 2. Fingerprint — deterministic hash
        val fingerprint = CrashFingerprint.compute(
            exceptionType = raw.exceptionType,
            stackTrace = sanitizedTrace,
        )

        // 3. Local dedup — skip LLM if we've seen this crash recently
        if (dedupCache.contains(fingerprint)) {
            dedupCache.touch(fingerprint)
            return CrashAnalysisResult.Duplicate(
                DuplicateCrashReport(
                    fingerprint = fingerprint,
                    newDevice = raw.device,
                    timestamp = raw.timestamp,
                )
            )
        }

        // 4. AI analysis — the expensive part
        return try {
            val executor = agentFactory.create()

            val parsed: ParsedStackTrace = executor.parseStackTrace(sanitizedTrace)
            val severity: SeverityResult = executor.classifySeverity(parsed, raw.device)
            val summary: CrashSummary = executor.generateSummary(
                parsed = parsed,
                severity = severity,
                screen = raw.currentScreen,
                breadcrumbs = sanitizedBreadcrumbs,
                device = raw.device,
            )

            // 5. Cache fingerprint to prevent re-processing
            dedupCache.put(fingerprint)

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
                    stepsToReproduce = summary.stepsToReproduce,
                    possibleCause = summary.possibleCause,
                    crashFile = parsed.crashFile,
                    crashLine = parsed.crashLine,
                    crashMethod = parsed.crashMethod,
                    isAppCode = parsed.isAppCode,
                    sanitizedStackTrace = sanitizedTrace,
                )
            )
        } catch (e: Exception) {
            // LLM failed — graceful fallback with basic info
            dedupCache.put(fingerprint)
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
}

/** Sealed result type for the crash analysis pipeline. */
internal sealed class CrashAnalysisResult {
    data class New(val report: ProcessedCrashReport) : CrashAnalysisResult()
    data class Duplicate(val report: DuplicateCrashReport) : CrashAnalysisResult()
    data class Fallback(val report: ProcessedCrashReport, val error: Exception) : CrashAnalysisResult()
}
