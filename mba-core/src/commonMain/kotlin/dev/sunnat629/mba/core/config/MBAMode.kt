package dev.sunnat629.mba.core.config

import dev.sunnat629.mba.core.ticket.TicketBackend

/**
 * Key ownership model — the security boundary.
 *
 * - SdkOnly: developer provides ALL keys. MBA owns ZERO secrets.
 * - Saas:    developer gets ONE project key. We own backend keys.
 * - SelfHosted: same as Saas on their infrastructure.
 */
sealed interface MBAMode {

    /** Open source mode. Developer provides all keys. */
    data class SdkOnly(
        val llmApiKey: String,
        val ticketBackend: TicketBackend,
    ) : MBAMode

    /** MBA Cloud SaaS. One project key. All integrations server-side. */
    data class Saas(
        val projectKey: String,
        val endpoint: String = "https://api.mobilebugagent.dev",
    ) : MBAMode

    /** Self-hosted. Same as Saas on customer infrastructure. */
    data class SelfHosted(
        val projectKey: String,
        val endpoint: String,
    ) : MBAMode
}
