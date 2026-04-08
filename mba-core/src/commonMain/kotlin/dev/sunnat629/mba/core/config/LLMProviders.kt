package dev.sunnat629.mba.core.config

/**
 * LLM provider configuration.
 * Developer gives a STRING (API key). MBA handles everything else.
 * Supports every provider Koog supports.
 */
object LLM {
    fun gemini(apiKey: String) = LLMConfig(Provider.GEMINI, apiKey, "gemini-2.0-flash")
    fun openAI(apiKey: String) = LLMConfig(Provider.OPENAI, apiKey, "gpt-4o-mini")
    fun anthropic(apiKey: String) = LLMConfig(Provider.ANTHROPIC, apiKey, "claude-sonnet-4-20250514")
    fun ollama(endpoint: String = "http://localhost:11434") =
        LLMConfig(Provider.OLLAMA, "", "llama3", endpoint)
    fun custom(apiKey: String, endpoint: String, model: String) =
        LLMConfig(Provider.CUSTOM, apiKey, model, endpoint)

    enum class Provider { GEMINI, OPENAI, ANTHROPIC, OLLAMA, CUSTOM }
}

data class LLMConfig(
    val provider: LLM.Provider,
    val apiKey: String,
    val model: String,
    val endpoint: String? = null,
) {
    /** Override the default model. Returns a new config. */
    fun model(model: String) = copy(model = model)

    companion object {
        /** Sentinel for SaaS mode where LLM is server-side. */
        val NONE = LLMConfig(LLM.Provider.GEMINI, "", "")
    }
}
