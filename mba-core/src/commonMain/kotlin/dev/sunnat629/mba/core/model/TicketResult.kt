package dev.sunnat629.mba.core.model

import kotlinx.serialization.Serializable

@Serializable
data class TicketResult(
    val ticketId: String,
    val backendName: String,
    val url: String? = null,
    val success: Boolean = true,
    val errorMessage: String? = null,
) {
    companion object {
        fun failure(backendName: String, error: String) = TicketResult(
            ticketId = "",
            backendName = backendName,
            success = false,
            errorMessage = error,
        )
    }
}
