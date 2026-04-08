package dev.sunnat629.mba.core.ticket

import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.TicketResult
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Abstraction for bug ticket creation.
 * MVP: NotionTicketBackend. Later: GitHubIssuesBackend, JiraBackend, LinearBackend.
 *
 * Implementations must be thread-safe.
 */
interface TicketBackend {
    val name: String

    /** Create a new bug ticket. Returns result with ticket ID and URL. */
    suspend fun createTicket(report: ProcessedCrashReport): TicketResult

    /** Update an existing ticket (increment count, add device, etc.). */
    suspend fun updateTicket(ticketId: String, update: TicketUpdate): TicketResult
}

@Serializable
data class TicketUpdate(
    val addDevice: DeviceContext? = null,
    val incrementCount: Boolean = false,
    val newOccurrenceTime: Instant? = null,
)
