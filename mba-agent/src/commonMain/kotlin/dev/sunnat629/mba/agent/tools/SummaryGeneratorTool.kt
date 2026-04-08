package dev.sunnat629.mba.agent.tools

import dev.sunnat629.mba.agent.model.CrashSummary
import dev.sunnat629.mba.agent.model.ParsedStackTrace
import dev.sunnat629.mba.agent.model.SeverityResult
import dev.sunnat629.mba.core.model.RawCrashReport

/**
 * MVP summary generator.
 *
 * Later: replace with Koog + Gemini structured JSON output.
 */
object SummaryGeneratorTool {

    fun generate(parsed: ParsedStackTrace, severity: SeverityResult, raw: RawCrashReport): CrashSummary {
        val location = listOfNotNull(parsed.crashFile, parsed.crashLine?.toString())
            .joinToString(":")
            .ifEmpty { "unknown location" }

        val title = buildString {
            if (!raw.currentScreen.isNullOrBlank()) {
                append(raw.currentScreen).append(": ")
            }
            append(parsed.rootException.substringAfterLast('.'))
            if (!parsed.crashMethod.isNullOrBlank()) {
                append(" in ").append(parsed.crashMethod)
            }
        }

        val description = "${parsed.rootException} at $location on thread '${raw.threadName}'. Severity=${severity.severity}."

        val steps = raw.breadcrumbs
            .takeIf { it.isNotEmpty() }
            ?.mapIndexed { idx, b -> "${idx + 1}. $b" }
            ?.joinToString("\n")

        val possibleCause = parsed.crashFile?.let { "Crash appears to originate in $it. Investigate nullability and lifecycle/concurrency around ${parsed.crashMethod ?: "the crashing method"}." }

        return CrashSummary(
            title = title.take(120),
            description = description.take(600),
            stepsToReproduce = steps,
            possibleCause = possibleCause,
        )
    }
}
