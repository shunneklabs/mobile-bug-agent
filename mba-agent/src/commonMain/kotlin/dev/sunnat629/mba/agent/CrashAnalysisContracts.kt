package dev.sunnat629.mba.agent

import dev.sunnat629.mba.agent.model.CombinedCrashAnalysis
import dev.sunnat629.mba.agent.model.CrashSummary
import dev.sunnat629.mba.agent.model.ParsedStackTrace
import dev.sunnat629.mba.agent.model.SeverityResult
import dev.sunnat629.mba.core.model.DeviceContext

/**
 * Common crash-analysis boundary.
 *
 * Platform/provider-specific executors live outside commonMain so future iOS
 * and Web/Wasm adapters do not inherit Android/JVM-only LLM client code.
 */
public interface CrashAnalysisExecutorFactory {
    fun create(): CrashAnalysisExecutor
}

/**
 * What the AI agent can do. Production implementations may use Koog or legacy
 * HTTP callers; tests use fakes.
 */
public interface CrashAnalysisExecutor {
    suspend fun analyzeCrash(
        sanitizedTrace: String,
        device: DeviceContext,
        screen: String?,
        breadcrumbs: List<String>,
        crashContext: String,
    ): CombinedCrashAnalysis? = null

    suspend fun parseStackTrace(sanitizedTrace: String): ParsedStackTrace
    suspend fun classifySeverity(parsed: ParsedStackTrace, device: DeviceContext): SeverityResult
    suspend fun generateSummary(
        parsed: ParsedStackTrace,
        severity: SeverityResult,
        screen: String?,
        breadcrumbs: List<String>,
        device: DeviceContext,
        crashContext: String,
    ): CrashSummary
}

internal fun extractJsonObjectPayload(response: String): String {
    val trimmed = response.trim()
    val firstBrace = trimmed.indexOf('{')
    if (firstBrace < 0) return trimmed

    var depth = 0
    var inString = false
    var escaped = false
    for (index in firstBrace until trimmed.length) {
        val char = trimmed[index]
        when {
            escaped -> escaped = false
            char == '\\' && inString -> escaped = true
            char == '"' -> inString = !inString
            !inString && char == '{' -> depth++
            !inString && char == '}' -> {
                depth--
                if (depth == 0) return trimmed.substring(firstBrace, index + 1)
            }
        }
    }

    return trimmed.substring(firstBrace)
}
