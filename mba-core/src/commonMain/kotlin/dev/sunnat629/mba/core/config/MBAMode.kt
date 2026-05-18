package dev.sunnat629.mba.core.config

import dev.sunnat629.mba.core.ticket.TicketBackend

/**
 * Deployment mode — determines the security/key ownership boundary.
 *
 * External devs pick ONE:
 * ```kotlin
 * // SDKOnly: app provides the provider/model/key or local endpoint
 * mode = MBAMode.SdkOnly(llm = LLM.ollama(model = "llama3.2:latest"))
 * mode = MBAMode.SdkOnly(llm = LLM.openAI("...").model("gpt-4o-mini"))
 *
 * // SaaS: one project key; backend owns provider/model routing
 * mode = MBAMode.Saas(projectKey = "proj_abc123")
 *
 * // Self-hosted: same as SaaS on their own infra
 * mode = MBAMode.SelfHosted(projectKey = "proj_abc123", endpoint = "https://...")
 * ```
 */
public sealed interface MBAMode {

    /** SDKOnly mode. Developer provides all provider keys/endpoints. MBA owns zero secrets. */
    public data class SdkOnly(
        /**
         * Backward-compatible Gemini key shortcut. Prefer [llm] for new code.
         */
        val llmApiKey: String = "",
        val ticketBackend: TicketBackend? = null,
        /**
         * Provider/model/endpoint selected by the host app. Supports Koog-backed
         * providers such as Gemini, OpenAI, Anthropic, Ollama, OpenRouter,
         * Mistral, DeepSeek, DashScope, and OpenAI-compatible custom endpoints.
         */
        val llm: LLMConfig? = null,
    ) : MBAMode

    /** MBA Cloud SaaS. One project key. All keys and integrations are server-side. */
    public data class Saas(
        val projectKey: String,
        val endpoint: String = "https://api.mobilebugagent.dev",
    ) : MBAMode

    /** Self-hosted. Same as SaaS on customer infrastructure. */
    public data class SelfHosted(
        val projectKey: String,
        val endpoint: String,
    ) : MBAMode
}
