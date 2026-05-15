package dev.sunnat629.mba.agent.model

import dev.sunnat629.mba.core.model.Severity
import kotlinx.serialization.Serializable

/**
 * Combined response from a single-prompt LLM call.
 * Contains everything that previously required 3 separate calls:
 * parsed stack trace + severity + summary.
 */
@Serializable
internal data class CombinedCrashAnalysis(
    // From ParsedStackTrace
    val rootException: String? = null,
    val rootMessage: String? = null,
    val crashFile: String? = null,
    val crashLine: Int? = null,
    val crashMethod: String? = null,
    val isAppCode: Boolean = false,
    val frameworkContext: String? = null,

    // From SeverityResult
    val severity: String = "MEDIUM",
    val confidence: Float = 0.5f,
    val severityReasoning: String? = null,

    // From CrashSummary
    val title: String = "Unknown crash",
    val description: String = "",
    val stepsToReproduce: String? = null,
    val possibleCause: String? = null,
) {
    fun toParsedStackTrace() = ParsedStackTrace(
        rootException = rootException ?: "Unknown",
        rootMessage = rootMessage,
        crashFile = crashFile,
        crashLine = crashLine,
        crashMethod = crashMethod,
        isAppCode = isAppCode,
    )

    fun toSeverityResult() = SeverityResult(
        severity = Severity.fromString(severity),
        confidence = confidence,
        reasoning = severityReasoning ?: "",
    )

    fun toCrashSummary() = CrashSummary(
        title = title,
        description = description,
        stepsToReproduce = stepsToReproduce,
        possibleCause = possibleCause,
    )
}
