package dev.sunnat629.mba.core.model

import kotlinx.serialization.Serializable

/**
 * Result of a ticket creation/update operation.
 *
 * **Public API** — returned by [TicketBackend] methods.
 */
@Serializable
public data class TicketResult(
    val ticketId: String,
    val backendName: String,
    val url: String? = null,
    val success: Boolean = true,
    val errorMessage: String? = null,
) {
    public companion object {
        /** Create a failure result. Use this instead of throwing from TicketBackend. */
        public fun failure(backendName: String, error: String): TicketResult = TicketResult(
            ticketId = "",
            backendName = backendName,
            success = false,
            errorMessage = error,
        )
    }
}
