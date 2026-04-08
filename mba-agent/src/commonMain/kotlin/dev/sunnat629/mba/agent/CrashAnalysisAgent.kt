package dev.sunnat629.mba.agent

import dev.sunnat629.mba.agent.model.CrashSummary
import dev.sunnat629.mba.agent.model.ParsedStackTrace
import dev.sunnat629.mba.agent.model.SeverityResult
import dev.sunnat629.mba.core.fingerprint.CrashFingerprint
import dev.sunnat629.mba.core.model.*
import dev.sunnat629.mba.core.pii.PIISanitizer
import dev.sunnat629.mba.core.store.LocalDedupCache

/**
 * The core pipeline. Orchestrates crash analysis:
 * 1. PII scrub (regex, no LLM)
 * 2. Fingerprint (SHA-256, no LLM)
 * 3. Local dedup (cache, no LLM)
 * 4. AI analysis (Koog agent — LLM calls happen here)
 *
 * Runs on background thread (WorkManager), never on main thread.
 */
class CrashAnalysisAgent(
    private val agentFactory: AgentFactory,
    private val piiSanitizer: PIISanitizer,
    private val dedupCache: LocalDedupCache,
) {
    suspend fun process(raw: RawCrashReport): CrashAnalysisResult {
        // 1. PII scrub — no LLM, ~1-3ms
        val sanitizedTrace = piiSanitizer.scrub(raw.stackTrace)
        val sanitizedMessage = raw.message?.let { piiSanitizer.scrub(it) }
        val sanitizedBreadcrumbs = raw.breadcrumbs.map { piiSanitizer.scrub(it) }

        // 2. Fingerprint — no LLM, <1ms
        val fingerprint = CrashFingerprint.compute(
            exceptionType = raw.exceptionType,
            stackTrace = sanitizedTrace,
        )

        // 3. Local dedup — no LLM, <1ms
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

        // 4. AI analysis — LLM calls (2-8 seconds)
        return try {
            val agent = agentFactory.create()

            val parsed: ParsedStackTrace = agent.parseStackTrace(sanitizedTrace)

            val severity: SeverityResult = agent.classifySeverity(
                parsed = parsed,
                device = raw.device,
            )

            val summary: CrashSummary = agent.generateSummary(
                parsed = parsed,
                severity = severity,
                screen = raw.currentScreen,
                breadcrumbs = sanitizedBreadcrumbs,
                device = raw.device,
            )

            // 5. Cache fingerprint
            dedupCache.put(fingerprint)

            // 6. Build result
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
            // LLM failed — fall back to raw report with basic info
            dedupCache.put(fingerprint)
            CrashAnalysisResult.Fallback(
                ProcessedCrashReport(
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

sealed class CrashAnalysisResult {
    data class New(val report: ProcessedCrashReport) : CrashAnalysisResult()
    data class Duplicate(val report: DuplicateCrashReport) : CrashAnalysisResult()
    data class Fallback(val report: ProcessedCrashReport, val error: Exception) : CrashAnalysisResult()
}
