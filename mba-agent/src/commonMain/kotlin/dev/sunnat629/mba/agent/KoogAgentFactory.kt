package dev.sunnat629.mba.agent

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import dev.sunnat629.mba.agent.model.CrashSummary
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
        val client = when (llmConfig.provider) {
            LLM.Provider.GEMINI -> GoogleLLMClient(llmConfig.apiKey)
            LLM.Provider.OPENAI -> OpenAILLMClient(llmConfig.apiKey)
            LLM.Provider.ANTHROPIC,
            LLM.Provider.OLLAMA,
            LLM.Provider.CUSTOM -> throw NotImplementedError(
                "Koog does not yet support ${llmConfig.provider} in this factory."
            )
        }
        MultiLLMPromptExecutor(
            when (llmConfig.provider) {
                LLM.Provider.GEMINI -> LLMProvider.Google
                LLM.Provider.OPENAI -> LLMProvider.OpenAI
                else -> throw IllegalStateException("Unsupported provider: ${llmConfig.provider}")
            } to client
        )
    }

    private val koogModel: LLModel by lazy {
        when (llmConfig.provider) {
            LLM.Provider.GEMINI -> GoogleModels.Gemini2_5Pro
            LLM.Provider.OPENAI -> OpenAIModels.Chat.GPT4o
            else -> throw IllegalStateException("Unsupported provider: ${llmConfig.provider}")
        }
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

    override suspend fun parseStackTrace(sanitizedTrace: String): ParsedStackTrace {
        val prompt = prompt("parse_stack_trace") {
            system(SystemPrompt.CRASH_ANALYST)
            user("${ToolPrompts.STACK_TRACE_PARSER}\n\nStack trace:\n$sanitizedTrace")
        }
        val response = promptExecutor.execute(prompt, model)
            .joinToString("") { it.content }
        return json.decodeFromString<ParsedStackTrace>(response)
    }

    override suspend fun classifySeverity(
        parsed: ParsedStackTrace,
        device: DeviceContext,
    ): SeverityResult {
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
        return json.decodeFromString<SeverityResult>(response)
    }

    override suspend fun generateSummary(
        parsed: ParsedStackTrace,
        severity: SeverityResult,
        screen: String?,
        breadcrumbs: List<String>,
        device: DeviceContext,
    ): CrashSummary {
        val input = buildString {
            appendLine("Parsed trace: ${json.encodeToString(parsed)}")
            appendLine("Severity: ${severity.severity} (${severity.reasoning})")
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
        return json.decodeFromString<CrashSummary>(response)
    }
}
