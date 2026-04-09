package dev.sunnat629.mba.agent

import dev.sunnat629.mba.agent.model.CrashSummary
import dev.sunnat629.mba.agent.model.ParsedStackTrace
import dev.sunnat629.mba.agent.model.SeverityResult
import dev.sunnat629.mba.agent.prompts.SystemPrompt
import dev.sunnat629.mba.agent.prompts.ToolPrompts
import dev.sunnat629.mba.core.config.LLM
import dev.sunnat629.mba.core.config.LLMConfig
import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.Severity
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.Closeable

/**
 * Factory that creates [CrashAnalysisExecutor] instances backed by the configured LLM.
 *
 * **Internal** — external devs never import this. The SDK wires it automatically.
 *
 * Owns a shared [HttpClient] — call [close] when done (e.g., in server shutdown).
 */
internal class AgentFactory(
    private val llmConfig: LLMConfig,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
) : Closeable {

    // Single shared HttpClient — reused across all LLM calls.
    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    // Lazily created, reused executor. Stateless — safe to share.
    private val executor: CrashAnalysisExecutor by lazy {
        val llmCaller = createLLMCaller(llmConfig)
        KoogCrashAnalysisExecutor(llmCaller, json)
    }

    /** Returns a reusable, stateless executor. */
    fun create(): CrashAnalysisExecutor = executor

    /** Release the shared HttpClient and its connection pool. */
    override fun close() {
        httpClient.close()
    }

    private fun createLLMCaller(config: LLMConfig): LLMCaller = when (config.provider) {
        LLM.Provider.GEMINI -> GeminiLLMCaller(
            httpClient = httpClient,
            apiKey = config.apiKey,
            model = config.model,
            json = json,
        )
        LLM.Provider.OPENAI -> OpenAILLMCaller(
            httpClient = httpClient,
            apiKey = config.apiKey,
            model = config.model,
            json = json,
        )
        LLM.Provider.ANTHROPIC -> throw NotImplementedError(
            "Anthropic provider is planned but not yet implemented."
        )
        LLM.Provider.OLLAMA -> throw NotImplementedError(
            "Ollama provider is planned but not yet implemented."
        )
        LLM.Provider.CUSTOM -> throw NotImplementedError(
            "Custom provider requires endpoint configuration. Not yet implemented."
        )
    }
}

/**
 * What the AI agent can do — defined as an interface for testability.
 * Production: backed by LLM. Tests: use a mock.
 */
internal interface CrashAnalysisExecutor {
    suspend fun parseStackTrace(sanitizedTrace: String): ParsedStackTrace
    suspend fun classifySeverity(parsed: ParsedStackTrace, device: DeviceContext): SeverityResult
    suspend fun generateSummary(
        parsed: ParsedStackTrace,
        severity: SeverityResult,
        screen: String?,
        breadcrumbs: List<String>,
        device: DeviceContext,
    ): CrashSummary
}

/** Abstraction for raw LLM calls. Isolated for testability. */
internal interface LLMCaller {
    suspend fun call(systemPrompt: String, userPrompt: String): String
}

/**
 * Production executor that orchestrates 3 LLM calls:
 * parseStackTrace → classifySeverity → generateSummary
 */
internal class KoogCrashAnalysisExecutor(
    private val llm: LLMCaller,
    private val json: Json,
) : CrashAnalysisExecutor {

    override suspend fun parseStackTrace(sanitizedTrace: String): ParsedStackTrace {
        val response = llm.call(
            systemPrompt = SystemPrompt.CRASH_ANALYST,
            userPrompt = "${ToolPrompts.STACK_TRACE_PARSER}\n\nStack trace:\n$sanitizedTrace",
        )
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
        val response = llm.call(
            systemPrompt = SystemPrompt.CRASH_ANALYST,
            userPrompt = "${ToolPrompts.SEVERITY_CLASSIFIER}\n\n$input",
        )
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
        val response = llm.call(
            systemPrompt = SystemPrompt.CRASH_ANALYST,
            userPrompt = "${ToolPrompts.SUMMARY_GENERATOR}\n\n$input",
        )
        return json.decodeFromString<CrashSummary>(response)
    }
}

// ─────────────────────────────────────────────────────────────────────── //
//  LLM Provider Implementations
// ─────────────────────────────────────────────────────────────────────── //

/**
 * Gemini LLM caller.
 *
 * SECURITY: API key is sent via `x-goog-api-key` header, NOT in the URL.
 * JSON request body is built with kotlinx.serialization (not string interpolation).
 */
internal class GeminiLLMCaller(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: String,
    private val json: Json,
) : LLMCaller {

    override suspend fun call(systemPrompt: String, userPrompt: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

        val requestBody = buildGeminiRequestBody(systemPrompt, userPrompt)

        val response = httpClient.post(url) {
            // API key in header — NOT in URL query string
            header("x-goog-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        if (response.status != HttpStatusCode.OK) {
            val errorBody = response.bodyAsText()
            throw RuntimeException("Gemini API error ${response.status}: $errorBody")
        }

        return extractTextFromGeminiResponse(response.bodyAsText())
    }

    /**
     * Build request body using kotlinx.serialization — safe against injection.
     */
    private fun buildGeminiRequestBody(systemPrompt: String, userPrompt: String): String {
        val body = buildJsonObject {
            putJsonObject("system_instruction") {
                putJsonObject("parts") {
                    put("text", systemPrompt)
                }
            }
            putJsonObject("contents") {
                putJsonObject("parts") {
                    put("text", userPrompt)
                }
            }
            putJsonObject("generationConfig") {
                put("response_mime_type", "application/json")
            }
        }
        return json.encodeToString(JsonObject.serializer(), body)
    }

    private fun extractTextFromGeminiResponse(responseBody: String): String {
        val root = json.parseToJsonElement(responseBody)
        return root.jsonObject["candidates"]
            ?.jsonArray?.getOrNull(0)
            ?.jsonObject?.get("content")
            ?.jsonObject?.get("parts")
            ?.jsonArray?.getOrNull(0)
            ?.jsonObject?.get("text")
            ?.jsonPrimitive?.content
            ?: throw RuntimeException("Failed to extract text from Gemini response: $responseBody")
    }
}

/**
 * OpenAI-compatible LLM caller (works with OpenAI, Azure OpenAI, etc.).
 *
 * SECURITY: API key sent via `Authorization: Bearer` header.
 */
internal class OpenAILLMCaller(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: String,
    private val json: Json,
) : LLMCaller {

    override suspend fun call(systemPrompt: String, userPrompt: String): String {
        val url = "https://api.openai.com/v1/chat/completions"

        val requestBody = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", userPrompt)
                }
            }
            putJsonObject("response_format") {
                put("type", "json_object")
            }
        }

        val response = httpClient.post(url) {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), requestBody))
        }

        if (response.status != HttpStatusCode.OK) {
            val errorBody = response.bodyAsText()
            throw RuntimeException("OpenAI API error ${response.status}: $errorBody")
        }

        val root = json.parseToJsonElement(response.bodyAsText())
        return root.jsonObject["choices"]
            ?.jsonArray?.getOrNull(0)
            ?.jsonObject?.get("message")
            ?.jsonObject?.get("content")
            ?.jsonPrimitive?.content
            ?: throw RuntimeException("Failed to extract content from OpenAI response")
    }
}
