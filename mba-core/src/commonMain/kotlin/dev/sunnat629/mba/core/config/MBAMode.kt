package dev.sunnat629.mba.core.config

import dev.sunnat629.mba.core.ticket.TicketBackend

/**
 * Deployment mode — determines the security/key ownership boundary.
 *
 * External devs pick ONE:
 * ```kotlin
 * // Open source: dev provides all keys
 * mode = MBAMode.SdkOnly(llmApiKey = "...")
 *
 * // SaaS: one project key, we handle the rest
 * mode = MBAMode.Saas(projectKey = "proj_abc123")
 *
 * // Self-hosted: same as SaaS on their own infra
 * mode = MBAMode.SelfHosted(projectKey = "proj_abc123", endpoint = "https://...")
 * ```
 */
public sealed interface MBAMode {

    /** Open source mode. Developer provides all keys. MBA owns zero secrets. */
    public data class SdkOnly(
        val llmApiKey: String,
        val ticketBackend: TicketBackend? = null,
    ) : MBAMode

    /** MBA Cloud SaaS. One project key. All integrations server-side. */
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
