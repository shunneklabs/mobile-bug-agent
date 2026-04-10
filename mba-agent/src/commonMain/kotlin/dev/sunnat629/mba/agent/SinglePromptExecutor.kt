package dev.sunnat629.mba.agent

import dev.sunnat629.mba.agent.model.CombinedCrashAnalysis
import dev.sunnat629.mba.agent.model.CrashSummary
import dev.sunnat629.mba.agent.model.ParsedStackTrace
import dev.sunnat629.mba.agent.model.SeverityResult
import dev.sunnat629.mba.agent.prompts.SystemPrompt
import dev.sunnat629.mba.core.model.DeviceContext
import kotlinx.serialization.json.Json

/**
 * Single-prompt executor that combines parse + classify + summarize into
 * ONE LLM call. Reduces latency from ~6-24s (3 calls) to ~2-8s (1 call).
 *
 * The LLM returns a single JSON object containing all fields.
 * If any field is missing, sensible defaults are used.
 */
internal class SinglePromptExecutor(
    private val llm: LLMCaller,
    private val json: Json,
) : CrashAnalysisExecutor {

    // Cache for the last combined analysis — used by the 3 interface methods
    // which are called in sequence by CrashAnalysisAgent.
    private var lastTrace: String? = null
    private var lastAnalysis: CombinedCrashAnalysis? = null

    override suspend fun parseStackTrace(sanitizedTrace: String): ParsedStackTrace {
        val analysis = analyze(sanitizedTrace, null, null, emptyList())
        return analysis.toParsedStackTrace()
    }

    override suspend fun classifySeverity(
        parsed: ParsedStackTrace,
        device: DeviceContext,
    ): SeverityResult {
        // If we already analyzed this trace, reuse the result
        return lastAnalysis?.toSeverityResult()
            ?: SeverityResult(
                severity = dev.sunnat629.mba.core.model.Severity.MEDIUM,
                confidence = 0.5f,
                reasoning = "Fallback — single prompt cache miss",
            )
    }

    override suspend fun generateSummary(
        parsed: ParsedStackTrace,
        severity: SeverityResult,
        screen: String?,
        breadcrumbs: List<String>,
        device: DeviceContext,
    ): CrashSummary {
        // If we already analyzed this trace, reuse the result
        return lastAnalysis?.toCrashSummary()
            ?: CrashSummary(
                title = "Unknown crash",
                description = "Single prompt cache miss",
            )
    }

    /**
     * The actual single LLM call. Called once per crash, result is cached
     * for the subsequent classifySeverity and generateSummary calls.
     */
    private suspend fun analyze(
        sanitizedTrace: String,
        screen: String?,
        device: DeviceContext?,
        breadcrumbs: List<String>,
    ): CombinedCrashAnalysis {
        // If we already analyzed this exact trace, return cached result
        if (sanitizedTrace == lastTrace && lastAnalysis != null) {
            return lastAnalysis!!
        }

        val userPrompt = buildString {
            appendLine(COMBINED_PROMPT)
            appendLine()
            appendLine("=== STACK TRACE ===")
            appendLine(sanitizedTrace)
            screen?.let {
                appendLine()
                appendLine("=== CURRENT SCREEN ===")
                appendLine(it)
            }
            if (breadcrumbs.isNotEmpty()) {
                appendLine()
                appendLine("=== BREADCRUMBS ===")
                appendLine(breadcrumbs.joinToString(" \u2192 "))
            }
            device?.let {
                appendLine()
                appendLine("=== DEVICE ===")
                appendLine(it.displayName)
                appendLine("Memory: ${it.availableMemoryMb}MB / ${it.totalMemoryMb}MB")
                if (it.isLowMemory) appendLine("LOW MEMORY WARNING")
            }
        }

        val response = llm.call(
            systemPrompt = SystemPrompt.CRASH_ANALYST,
            userPrompt = userPrompt,
        )

        val analysis = json.decodeFromString<CombinedCrashAnalysis>(response)
        lastTrace = sanitizedTrace
        lastAnalysis = analysis
        return analysis
    }

    companion object {
        private val COMBINED_PROMPT = """
            Analyze this Android crash completely in ONE response. Return JSON with ALL fields:
            {
              "rootException": "java.lang.NullPointerException",
              "rootMessage": "Attempt to invoke method on null",
              "crashFile": "CheckoutViewModel.kt",
              "crashLine": 87,
              "crashMethod": "processPayment",
              "isAppCode": true,
              "frameworkContext": "Coroutine after ViewModel cleared",
              "severity": "HIGH",
              "confidence": 0.85,
              "severityReasoning": "Main thread crash in payment flow",
              "title": "Checkout crashes during payment processing",
              "description": "NullPointerException in CheckoutViewModel.processPayment...",
              "stepsToReproduce": "1. Open checkout\n2. Start payment\n3. Rotate device",
              "possibleCause": "Payment coroutine outlives ViewModel lifecycle"
            }
            
            Severity levels: CRITICAL (data loss/security), HIGH (main flow), MEDIUM (edge case), LOW (cosmetic).
            Title format: "[Screen] [what happens] [trigger]".
            Focus on app code frames (not android.*, java.*, kotlin.*).
            Set null for fields you cannot determine. Do not guess.
        """.trimIndent()
    }
}
