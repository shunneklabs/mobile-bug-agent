package dev.sunnat629.mba.agent.tools

import dev.sunnat629.mba.agent.model.ParsedStackTrace
import dev.sunnat629.mba.agent.model.SeverityResult
import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.Severity

/**
 * MVP heuristic severity classifier.
 *
 * Later: replace with Koog + Gemini classification.
 */
object SeverityClassifierTool {

    fun classify(parsed: ParsedStackTrace, device: DeviceContext): SeverityResult {
        val ex = parsed.rootException.lowercase()

        val severity = when {
            ex.contains("outofmemory") -> Severity.CRITICAL
            ex.contains("security") -> Severity.CRITICAL
            parsed.crashFile != null && parsed.isAppCode -> Severity.HIGH
            else -> Severity.MEDIUM
        }

        val confidence = when (severity) {
            Severity.CRITICAL -> 0.7f
            Severity.HIGH -> 0.65f
            Severity.MEDIUM -> 0.55f
            Severity.LOW -> 0.5f
        }

        val reasoning = buildString {
            append("Classified as ").append(severity.name).append(" based on exception type and whether the crash is in app code.")
            if (device.isLowMemory) append(" Device reports low memory.")
        }

        return SeverityResult(
            severity = severity,
            confidence = confidence,
            reasoning = reasoning,
        )
    }
}
