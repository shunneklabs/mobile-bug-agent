package dev.sunnat629.mba.agent

import dev.sunnat629.mba.agent.model.CombinedCrashAnalysis
import dev.sunnat629.mba.agent.model.CrashSummary
import dev.sunnat629.mba.agent.model.ParsedStackTrace
import dev.sunnat629.mba.agent.model.SeverityResult
import dev.sunnat629.mba.agent.prompts.SystemPrompt
import dev.sunnat629.mba.core.model.DeviceContext
import kotlinx.serialization.json.Json

internal class SinglePromptExecutor(
    private val llm: LLMCaller,
    private val json: Json,
) : CrashAnalysisExecutor {

    private var lastTrace: String? = null
    private var lastAnalysis: CombinedCrashAnalysis? = null

    override suspend fun parseStackTrace(sanitizedTrace: String): ParsedStackTrace {
        val analysis = analyze(sanitizedTrace)
        return analysis.toParsedStackTrace()
    }

    override suspend fun classifySeverity(
        parsed: ParsedStackTrace,
        device: DeviceContext,
    ): SeverityResult {
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
        return lastAnalysis?.toCrashSummary()
            ?: CrashSummary(title = "Unknown crash", description = "Single prompt cache miss")
    }

    private suspend fun analyze(sanitizedTrace: String): CombinedCrashAnalysis {
        if (sanitizedTrace == lastTrace && lastAnalysis != null) {
            return lastAnalysis!!
        }

        val userPrompt = buildString {
            appendLine(COMBINED_PROMPT)
            appendLine()
            appendLine("=== STACK TRACE ===")
            appendLine(sanitizedTrace)
        }

        val response = llm.call(
            systemPrompt = SystemPrompt.CRASH_ANALYST,
            userPrompt = userPrompt,
        )

        val analysis = json.decodeFromString<CombinedCrashAnalysis>(extractJsonObjectPayload(response))
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
