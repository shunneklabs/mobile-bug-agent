package dev.sunnat629.mba.core.config

/**
 * LLM provider factory.
 *
 * External devs use these one-liners:
 * ```kotlin
 * llm = LLM.gemini("your-api-key").model("gemini-2.5-pro")
 * llm = LLM.openAI("your-api-key").model("gpt-4o-mini")
 * llm = LLM.ollama(model = "llama3.2:latest") // local, no key needed
 * ```
 *
 * The API key is NEVER logged, serialized, or sent anywhere except
 * the provider's own endpoint via secure headers.
 */
public object LLM {
    public fun gemini(
        apiKey: String,
        model: String = "gemini-2.5-flash",
        endpoint: String? = null,
    ): LLMConfig = LLMConfig(Provider.GEMINI, apiKey, model, endpoint)

    public fun openAI(
        apiKey: String,
        model: String = "gpt-4o-mini",
        endpoint: String? = null,
    ): LLMConfig = LLMConfig(Provider.OPENAI, apiKey, model, endpoint)

    public fun anthropic(
        apiKey: String,
        model: String = "claude-sonnet-4-20250514",
        endpoint: String? = null,
    ): LLMConfig = LLMConfig(Provider.ANTHROPIC, apiKey, model, endpoint)

    public fun ollama(
        model: String = "llama3.2:latest",
        endpoint: String = "http://localhost:11434",
    ): LLMConfig = LLMConfig(Provider.OLLAMA, "", model, endpoint)

    public fun openRouter(
        apiKey: String,
        model: String = "microsoft/phi-4-reasoning:free",
        endpoint: String? = null,
    ): LLMConfig = LLMConfig(Provider.OPENROUTER, apiKey, model, endpoint)

    public fun mistral(
        apiKey: String,
        model: String = "mistral-large-latest",
        endpoint: String? = null,
    ): LLMConfig = LLMConfig(Provider.MISTRAL, apiKey, model, endpoint)

    public fun deepSeek(
        apiKey: String,
        model: String = "deepseek-chat",
        endpoint: String? = null,
    ): LLMConfig = LLMConfig(Provider.DEEPSEEK, apiKey, model, endpoint)

    public fun dashScope(
        apiKey: String,
        model: String = "qwen-plus",
        endpoint: String? = null,
    ): LLMConfig = LLMConfig(Provider.DASHSCOPE, apiKey, model, endpoint)

    /**
     * OpenAI-compatible endpoint for local or hosted gateways such as LM Studio,
     * vLLM, LiteLLM, or an app-owned proxy. The endpoint should be the API base
     * URL, for example `http://10.0.2.2:1234/v1`.
     */
    public fun custom(
        apiKey: String = "",
        endpoint: String,
        model: String,
    ): LLMConfig = LLMConfig(Provider.CUSTOM, apiKey, model, endpoint)

    public enum class Provider {
        GEMINI,
        OPENAI,
        ANTHROPIC,
        OLLAMA,
        OPENROUTER,
        MISTRAL,
        DEEPSEEK,
        DASHSCOPE,
        CUSTOM,
    }
}

/**
 * Immutable LLM configuration. Created via [LLM] factory methods.
 *
 * The [apiKey] is deliberately excluded from [toString] to prevent
 * accidental logging.
 */
public data class LLMConfig(
    val provider: LLM.Provider,
    val apiKey: String,
    val model: String,
    val endpoint: String? = null,
) {
    public val requiresApiKey: Boolean
        get() = provider !in setOf(LLM.Provider.OLLAMA, LLM.Provider.CUSTOM)

    /** Override the default model. Returns a new config. */
    public fun model(model: String): LLMConfig = copy(model = model)

    /** Override the provider endpoint. Returns a new config. */
    public fun endpoint(endpoint: String): LLMConfig = copy(endpoint = endpoint)

    /** Prevent API key from leaking into logs. */
    override fun toString(): String =
        "LLMConfig(provider=$provider, model=$model, endpoint=$endpoint, apiKey=***)"

    public companion object {
        /** Sentinel for SaaS/SelfHosted modes where LLM is server-side. */
        public val NONE: LLMConfig = LLMConfig(LLM.Provider.CUSTOM, "", "")
    }
}
