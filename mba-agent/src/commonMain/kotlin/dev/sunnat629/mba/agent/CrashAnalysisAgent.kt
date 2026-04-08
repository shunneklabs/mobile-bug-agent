package dev.sunnat629.mba.agent

import dev.sunnat629.mba.agent.model.CrashSummary
import dev.sunnat629.mba.agent.model.ParsedStackTrace
import dev.sunnat629.mba.agent.model.SeverityResult
import dev.sunnat629.mba.agent.tools.SeverityClassifierTool
import dev.sunnat629.mba.agent.tools.StackTraceParserTool
import dev.sunnat629.mba.agent.tools.SummaryGeneratorTool
import dev.sunnat629.mba.core.fingerprint.CrashFingerprint
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.pii.PIISanitizer
import dev.sunnat629.mba.core.store.LocalDedupCache

/**
 * Orchestrates the crash analysis pipeline.
 *
 * Current MVP is deterministic and does not call the LLM yet.
 * Next step is to wire Koog + Gemini structured JSON calls behind these tools.
 */
class CrashAnalysisAgent(
    private val piiSanitizer: PIISanitizer,
    private val dedupCache: LocalDedupCache,
) {

    suspend fun process(raw: RawCrashReport): CrashAnalysisResult {
        val sanitizedTrace = piiSanitizer.scrub(raw.stackTrace)
        val fingerprint = CrashFingerprint.compute(raw.exceptionType, sanitizedTrace)

        if (dedupCache.contains(fingerprint)) {
            dedupCache.touch(fingerprint)
            return CrashAnalysisResult.Duplicate(fingerprint)
        }

        val parsed: ParsedStackTrace = StackTraceParserTool.parse(sanitizedTrace)
        val severity: SeverityResult = SeverityClassifierTool.classify(parsed, raw.device)
        val summary: CrashSummary = SummaryGeneratorTool.generate(parsed, severity, raw)

        dedupCache.put(fingerprint)

        val report = ProcessedCrashReport(
            raw = raw,
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

        return CrashAnalysisResult.New(report)
    }
}

sealed class CrashAnalysisResult {
    data class New(val report: ProcessedCrashReport) : CrashAnalysisResult()
    data class Duplicate(val fingerprint: String) : CrashAnalysisResult()
}
