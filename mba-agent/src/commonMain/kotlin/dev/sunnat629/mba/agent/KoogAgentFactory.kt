package dev.sunnat629.mba.agent

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.modelsById
import ai.koog.prompt.executor.clients.dashscope.DashscopeClientSettings
import ai.koog.prompt.executor.clients.dashscope.DashscopeLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.mistralai.MistralAIClientSettings
import ai.koog.prompt.executor.clients.mistralai.MistralAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import dev.sunnat629.mba.agent.model.CrashSummary
import dev.sunnat629.mba.agent.model.CombinedCrashAnalysis
import dev.sunnat629.mba.agent.model.ParsedStackTrace
import dev.sunnat629.mba.agent.model.SeverityResult
import dev.sunnat629.mba.agent.prompts.SystemPrompt
import dev.sunnat629.mba.agent.prompts.ToolPrompts
import dev.sunnat629.mba.core.config.LLM
import dev.sunnat629.mba.core.config.LLMConfig
import dev.sunnat629.mba.core.model.DeviceContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable

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

internal fun koogModelForConfig(llmConfig: LLMConfig): LLModel {
    val provider = llmConfig.koogProvider()
    return when (llmConfig.provider) {
        LLM.Provider.GEMINI -> GoogleModels.modelsById()[llmConfig.model]
            ?: fallbackChatModel(provider, llmConfig.model)
        else -> fallbackChatModel(provider, llmConfig.model)
    }
}

private fun fallbackChatModel(provider: LLMProvider, model: String): LLModel =
    LLModel(
        provider = provider,
        id = model,
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Temperature,
            LLMCapability.MultipleChoices,
        ),
    )

private fun LLMConfig.koogProvider(): LLMProvider = when (provider) {
    LLM.Provider.GEMINI -> LLMProvider.Google
    LLM.Provider.OPENAI -> LLMProvider.OpenAI
    LLM.Provider.ANTHROPIC -> LLMProvider.Anthropic
    LLM.Provider.OLLAMA -> LLMProvider.Ollama
    LLM.Provider.OPENROUTER -> LLMProvider.OpenRouter
    LLM.Provider.MISTRAL -> LLMProvider.MistralAI
    LLM.Provider.DEEPSEEK -> LLMProvider.DeepSeek
    LLM.Provider.DASHSCOPE -> LLMProvider.Alibaba
    LLM.Provider.CUSTOM -> LLMProvider.OpenAI
}

/**
 * Factory that creates [CrashAnalysisExecutor] instances backed by Koog.
 *
 * Replaces the legacy [AgentFactory] when [useKoog] = true.
 * Keeps legacy HTTP callers behind a flag for rollback.
 */
internal class KoogAgentFactory(
    private val llmConfig: LLMConfig,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
) : Closeable {

    private val koogExecutor: PromptExecutor by lazy {
        val provider = llmConfig.koogProvider()
        val client = when (llmConfig.provider) {
            LLM.Provider.GEMINI -> GoogleLLMClient(
                apiKey = llmConfig.apiKey,
                settings = GoogleClientSettings(
                    baseUrl = llmConfig.endpoint ?: "https://generativelanguage.googleapis.com",
                ),
            )
            LLM.Provider.OPENAI -> OpenAILLMClient(
                apiKey = llmConfig.apiKey,
                settings = OpenAIClientSettings(
                    baseUrl = llmConfig.endpoint ?: "https://api.openai.com",
                ),
            )
            LLM.Provider.ANTHROPIC -> AnthropicLLMClient(
                apiKey = llmConfig.apiKey,
                settings = AnthropicClientSettings(
                    baseUrl = llmConfig.endpoint ?: "https://api.anthropic.com",
                ),
            )
            LLM.Provider.OLLAMA -> OllamaClient(
                baseUrl = llmConfig.endpoint ?: "http://localhost:11434",
            )
            LLM.Provider.OPENROUTER -> OpenRouterLLMClient(
                apiKey = llmConfig.apiKey,
                settings = OpenRouterClientSettings(
                    baseUrl = llmConfig.endpoint ?: "https://openrouter.ai",
                ),
            )
            LLM.Provider.MISTRAL -> MistralAILLMClient(
                apiKey = llmConfig.apiKey,
                settings = MistralAIClientSettings(
                    baseUrl = llmConfig.endpoint ?: "https://api.mistral.ai",
                ),
            )
            LLM.Provider.DEEPSEEK -> DeepSeekLLMClient(
                apiKey = llmConfig.apiKey,
                settings = DeepSeekClientSettings(
                    baseUrl = llmConfig.endpoint ?: "https://api.deepseek.com",
                ),
            )
            LLM.Provider.DASHSCOPE -> DashscopeLLMClient(
                apiKey = llmConfig.apiKey,
                settings = DashscopeClientSettings(
                    baseUrl = llmConfig.endpoint ?: "https://dashscope-intl.aliyuncs.com/",
                ),
            )
            LLM.Provider.CUSTOM -> OpenAILLMClient(
                apiKey = llmConfig.apiKey,
                settings = OpenAIClientSettings(
                    baseUrl = requireNotNull(llmConfig.endpoint) {
                        "Custom LLM provider requires endpoint."
                    },
                ),
            )
        }
        MultiLLMPromptExecutor(provider to client)
    }

    private val koogModel: LLModel by lazy {
        koogModelForConfig(llmConfig)
    }

    private val executor: CrashAnalysisExecutor by lazy {
        KoogCrashAnalysisExecutor(koogExecutor, koogModel, json)
    }

    /** Returns a reusable executor. */
    fun create(): CrashAnalysisExecutor = executor

    override fun close() {
        // Koog PromptExecutor owns its clients; no explicit close needed yet.
    }

}

/**
 * Koog-backed crash analysis executor.
 * Uses Koog [PromptExecutor] for all LLM calls.
 */
internal class KoogCrashAnalysisExecutor(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val json: Json,
) : CrashAnalysisExecutor {

    private var lastTrace: String? = null
    private var lastAnalysis: CombinedCrashAnalysis? = null

    override suspend fun analyzeCrash(
        sanitizedTrace: String,
        device: DeviceContext,
        screen: String?,
        breadcrumbs: List<String>,
        crashContext: String,
    ): CombinedCrashAnalysis {
        if (sanitizedTrace == lastTrace && lastAnalysis != null) {
            return lastAnalysis!!
        }

        val input = buildString {
            appendLine(COMBINED_ANALYSIS_PROMPT)
            appendLine()
            appendLine("=== CRASH CONTEXT ===")
            appendLine(crashContext)
            screen?.let { appendLine("- Current Screen: $it") }
            if (breadcrumbs.isNotEmpty()) {
                appendLine("- Breadcrumbs:")
                breadcrumbs.forEachIndexed { index, breadcrumb ->
                    appendLine("  ${index + 1}. $breadcrumb")
                }
            }
            appendLine("- Device: ${device.displayName}")
            appendLine("- OS: Android ${device.osVersion} (API ${device.sdkInt})")
            appendLine()
            appendLine("=== STACK TRACE ===")
            appendLine(sanitizedTrace)
        }

        val prompt = prompt("analyze_crash_full") {
            system(SystemPrompt.CRASH_ANALYST)
            user(input)
        }
        val response = promptExecutor.execute(prompt, model)
            .joinToString("") { it.content }
        val analysis = json.decodeFromString<CombinedCrashAnalysis>(extractJsonObjectPayload(response))
        lastTrace = sanitizedTrace
        lastAnalysis = analysis
        return analysis
    }

    override suspend fun parseStackTrace(sanitizedTrace: String): ParsedStackTrace {
        lastAnalysis?.takeIf { sanitizedTrace == lastTrace }?.let {
            return it.toParsedStackTrace()
        }
        val prompt = prompt("parse_stack_trace") {
            system(SystemPrompt.CRASH_ANALYST)
            user("${ToolPrompts.STACK_TRACE_PARSER}\n\nStack trace:\n$sanitizedTrace")
        }
        val response = promptExecutor.execute(prompt, model)
            .joinToString("") { it.content }
        return json.decodeFromString<ParsedStackTrace>(extractJsonObjectPayload(response))
    }

    override suspend fun classifySeverity(
        parsed: ParsedStackTrace,
        device: DeviceContext,
    ): SeverityResult {
        lastAnalysis?.let {
            return it.toSeverityResult()
        }
        val input = buildString {
            appendLine("Parsed trace: ${json.encodeToString(parsed)}")
            appendLine("Device: ${device.displayName}, Memory: ${device.availableMemoryMb}MB/${device.totalMemoryMb}MB")
        }
        val prompt = prompt("classify_severity") {
            system(SystemPrompt.CRASH_ANALYST)
            user("${ToolPrompts.SEVERITY_CLASSIFIER}\n\n$input")
        }
        val response = promptExecutor.execute(prompt, model)
            .joinToString("") { it.content }
        return json.decodeFromString<SeverityResult>(extractJsonObjectPayload(response))
    }

    override suspend fun generateSummary(
        parsed: ParsedStackTrace,
        severity: SeverityResult,
        screen: String?,
        breadcrumbs: List<String>,
        device: DeviceContext,
        crashContext: String,
    ): CrashSummary {
        lastAnalysis?.let {
            return it.toCrashSummary()
        }
        val input = buildString {
            appendLine("Parsed trace: ${json.encodeToString(parsed)}")
            appendLine("Severity: ${severity.severity} (${severity.reasoning})")
            appendLine(crashContext)
            screen?.let { appendLine("Current screen: $it") }
            if (breadcrumbs.isNotEmpty()) {
                appendLine("Breadcrumbs: ${breadcrumbs.joinToString(" → ")}")
            }
            appendLine("Device: ${device.displayName}")
        }
        val prompt = prompt("generate_summary") {
            system(SystemPrompt.CRASH_ANALYST)
            user("${ToolPrompts.SUMMARY_GENERATOR}\n\n$input")
        }
        val response = promptExecutor.execute(prompt, model)
            .joinToString("") { it.content }
        return json.decodeFromString<CrashSummary>(extractJsonObjectPayload(response))
    }

    private companion object {
        val COMBINED_ANALYSIS_PROMPT: String = """
            Analyze this Android crash and return ONE JSON object:
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
              "description": "NullPointerException in CheckoutViewModel.processPayment on app version/build from context.",
              "stepsToReproduce": "1. Open checkout\n2. Start payment\n3. Repeat the last recorded action",
              "possibleCause": "Payment response is null before processPayment reads it"
            }

            Rules:
            - Return JSON only. No markdown fences.
            - stepsToReproduce MUST be non-null. Use breadcrumbs and current screen when present.
            - possibleCause MUST be non-null. Tie it to exception type, app frame, method, lifecycle, or ANR context.
            - description MUST mention failing method/file when known and include app version/build context when provided.
            - Focus on app frames, not android.*, androidx.*, java.*, or kotlin.* frames.
            - Severity: CRITICAL for data loss/security/payment blocker, HIGH for main user flow crash, MEDIUM for edge case, LOW for cosmetic/rare.
            - Use confidence between 0.0 and 1.0. If unsure, use 0.5 rather than 0.0.
            - Set nullable location fields to null only when they cannot be determined.
        """.trimIndent()
    }
}
