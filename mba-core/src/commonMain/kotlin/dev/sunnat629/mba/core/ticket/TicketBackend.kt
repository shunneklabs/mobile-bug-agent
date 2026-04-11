package dev.sunnat629.mba.core.ticket

import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.TicketResult
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Abstraction for bug ticket creation.
 *
 * **Public API** — external devs implement this to plug in custom backends.
 * Built-in: NotionTicketBackend (in mba-notion module).
 * Planned: GitHubIssuesBackend, JiraBackend, LinearBackend.
 *
 * Contract:
 * - Implementations MUST be thread-safe (called from background threads).
 * - MUST NOT throw — return [TicketResult.failure] instead.
 */
public interface TicketBackend {
    /** Human-readable name (e.g., "Notion", "Jira"). Used in logs and results. */
    public val name: String

    /** Create a new bug ticket from a processed crash report. */
    public suspend fun createTicket(report: ProcessedCrashReport): TicketResult

    /** Update an existing ticket (increment count, add device, etc.). */
    public suspend fun updateTicket(ticketId: String, update: TicketUpdate): TicketResult
}

@Serializable
public data class TicketUpdate(
    val addDevice: DeviceContext? = null,
    val incrementCount: Boolean = false,
    val newOccurrenceTime: Instant? = null,
)
