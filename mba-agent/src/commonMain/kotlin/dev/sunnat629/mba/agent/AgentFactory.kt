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

/**
 * Creates the Koog AI agent with the right LLM executor.
 * Maps our LLMConfig → Koog's executor system.
 * The developer never imports Koog classes.
 */
class AgentFactory(
    private val llmConfig: LLMConfig,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
) {
    /**
     * Create a CrashAnalysisExecutor backed by the configured LLM.
     * Each call creates a fresh executor (stateless).
     */
    fun create(): CrashAnalysisExecutor {
        // In production, this creates a Koog agent with the right LLM provider.
        // For now, we define the interface that Koog integration will fulfill.
        val llmCaller = createLLMCaller(llmConfig)
        return KoogCrashAnalysisExecutor(llmCaller, json)
    }

    private fun createLLMCaller(config: LLMConfig): LLMCaller = when (config.provider) {
        LLM.Provider.GEMINI -> KoogLLMCaller(provider = "gemini", apiKey = config.apiKey, model = config.model)
        LLM.Provider.OPENAI -> KoogLLMCaller(provider = "openai", apiKey = config.apiKey, model = config.model)
        LLM.Provider.ANTHROPIC -> KoogLLMCaller(provider = "anthropic", apiKey = config.apiKey, model = config.model)
        LLM.Provider.OLLAMA -> KoogLLMCaller(provider = "ollama", apiKey = "", model = config.model, endpoint = config.endpoint)
        LLM.Provider.CUSTOM -> KoogLLMCaller(provider = "custom", apiKey = config.apiKey, model = config.model, endpoint = config.endpoint)
    }
}

/**
 * What the agent can do — defined as an interface so it's testable.
 * Production implementation uses Koog. Tests use a mock.
 */
interface CrashAnalysisExecutor {
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

/**
 * Abstraction for LLM calls. Koog implementation lives in the actual agent module.
 * This keeps the agent testable without real LLM calls.
 */
interface LLMCaller {
    suspend fun call(systemPrompt: String, userPrompt: String): String
}

/**
 * Production Koog-backed implementation.
 * Wires system prompt + tool prompts + structured output.
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
            appendLine("Parsed trace: ${json.encodeToString(ParsedStackTrace.serializer(), parsed)}")
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
            appendLine("Parsed trace: ${json.encodeToString(ParsedStackTrace.serializer(), parsed)}")
            appendLine("Severity: ${severity.severity} (${severity.reasoning})")
            screen?.let { appendLine("Current screen: $it") }
            if (breadcrumbs.isNotEmpty()) {
                appendLine("Breadcrumbs: ${breadcrumbs.joinToString(" \u2192 ")}")
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

/**
 * Koog-backed LLM caller. This is where Koog's actual API gets called.
 * Isolated here so it's the ONLY class that imports Koog.
 */
internal class KoogLLMCaller(
    private val provider: String,
    private val apiKey: String,
    private val model: String,
    private val endpoint: String? = null,
) : LLMCaller {

    // Using Ktor for the actual LLM call to ensure multiplatform compatibility 
    // and predictable behavior, as Koog's internal executors might have 
    // platform-specific nuances or requires specific setup.
    private val httpClient = io.ktor.client.HttpClient {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    override suspend fun call(systemPrompt: String, userPrompt: String): String {
        return when (provider) {
            "gemini" -> callGemini(systemPrompt, userPrompt)
            else -> throw NotImplementedError("Provider $provider not yet implemented.")
        }
    }

    private suspend fun callGemini(systemPrompt: String, userPrompt: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        
        val requestBody = buildGeminiRequest(systemPrompt, userPrompt)
        
        val response: io.ktor.client.statement.HttpResponse = httpClient.post(url) {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(requestBody)
        }
        
        if (response.status != io.ktor.http.HttpStatusCode.OK) {
            val errorBody = response.bodyAsText()
            throw RuntimeException("Gemini API call failed: ${response.status}\n$errorBody")
        }

        val responseBody = response.bodyAsText()
        return extractJsonFromGeminiResponse(responseBody)
    }

    private fun buildGeminiRequest(systemPrompt: String, userPrompt: String): String {
        // Simple Gemini content structure
        return """
            {
              "system_instruction": {
                "parts": { "text": ${Json.encodeToString(systemPrompt)} }
              },
              "contents": {
                "parts": { "text": ${Json.encodeToString(userPrompt)} }
              },
              "generationConfig": {
                "response_mime_type": "application/json"
              }
            }
        """.trimIndent()
    }

    private fun extractJsonFromGeminiResponse(response: String): String {
        // Gemini returns a complex JSON. We need to extract candidates[0].content.parts[0].text
        val jsonElement = Json.parseToJsonElement(response)
        return jsonElement.jsonObject["candidates"]
            ?.jsonArray?.get(0)
            ?.jsonObject?.get("content")
            ?.jsonObject?.get("parts")
            ?.jsonArray?.get(0)
            ?.jsonObject?.get("text")
            ?.jsonPrimitive?.content ?: throw RuntimeException("Failed to extract text from Gemini response")
    }
}
