package dev.sunnat629.mba.core.config

/**
 * LLM provider factory.
 *
 * External devs use these one-liners:
 * ```kotlin
 * llm = LLM.gemini("your-api-key")
 * llm = LLM.openAI("your-api-key")
 * llm = LLM.ollama() // local, no key needed
 * ```
 *
 * The API key is NEVER logged, serialized, or sent anywhere except
 * the provider's own endpoint via secure headers.
 */
public object LLM {
    public fun gemini(apiKey: String): LLMConfig =
        LLMConfig(Provider.GEMINI, apiKey, "gemini-2.0-flash")

    public fun openAI(apiKey: String): LLMConfig =
        LLMConfig(Provider.OPENAI, apiKey, "gpt-4o-mini")

    public fun anthropic(apiKey: String): LLMConfig =
        LLMConfig(Provider.ANTHROPIC, apiKey, "claude-sonnet-4-20250514")

    public fun ollama(endpoint: String = "http://localhost:11434"): LLMConfig =
        LLMConfig(Provider.OLLAMA, "", "llama3", endpoint)

    public fun custom(apiKey: String, endpoint: String, model: String): LLMConfig =
        LLMConfig(Provider.CUSTOM, apiKey, model, endpoint)

    public enum class Provider { GEMINI, OPENAI, ANTHROPIC, OLLAMA, CUSTOM }
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
    /** Override the default model. Returns a new config. */
    public fun model(model: String): LLMConfig = copy(model = model)

    /** Prevent API key from leaking into logs. */
    override fun toString(): String =
        "LLMConfig(provider=$provider, model=$model, endpoint=$endpoint, apiKey=***)"

    public companion object {
        /** Sentinel for SaaS/SelfHosted modes where LLM is server-side. */
        public val NONE: LLMConfig = LLMConfig(LLM.Provider.GEMINI, "", "")
    }
}
