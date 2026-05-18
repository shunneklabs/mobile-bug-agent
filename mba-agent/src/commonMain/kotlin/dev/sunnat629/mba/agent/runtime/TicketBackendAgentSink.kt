package dev.sunnat629.mba.agent.runtime

import dev.sunnat629.mba.core.model.TicketResult
import dev.sunnat629.mba.core.ticket.CrashOccurrenceTicketBackend
import dev.sunnat629.mba.core.ticket.TicketBackend
import dev.sunnat629.mba.core.ticket.TicketUpdate

/**
 * Adapts any optional [TicketBackend] module into the SDKOnly agent sink API.
 *
 * This keeps platform adapters free of concrete Notion/GitHub/Jira/etc.
 * dependencies. Backends that also implement [CrashOccurrenceTicketBackend]
 * receive linked occurrence rows for duplicate crashes.
 */
public class TicketBackendAgentSink(
    private val backend: TicketBackend,
) : MBAAgentSink {
    override val name: String = backend.name

    override suspend fun sync(event: MBAAgentEvent): MBAAgentSinkResult {
        val existingTicketId = existingTicketId(event)
        return if (!event.isNewGroup && existingTicketId != null) {
            val update = backend.updateTicket(
                existingTicketId,
                TicketUpdate(
                    incrementCount = true,
                    occurrenceCount = event.group.occurrenceCount,
                    uniqueDeviceCount = event.group.uniqueDeviceCount,
                    newOccurrenceTime = event.occurrence.occurredAt,
                    deviceMatrix = event.occurrence.deviceModel,
                    report = event.report,
                ),
            )
            if (backend is CrashOccurrenceTicketBackend) {
                backend.createCrashOccurrence(event.report, existingTicketId)
            }
            update.toSinkResult(created = false)
        } else {
            backend.createTicket(event.report).toSinkResult(created = true)
        }
    }

    private fun existingTicketId(event: MBAAgentEvent): String? =
        when (backend.name.lowercase()) {
            "notion" -> event.group.notionTicketId
            "github" -> event.group.githubIssueId
            else -> null
        }

    private fun TicketResult.toSinkResult(created: Boolean): MBAAgentSinkResult =
        MBAAgentSinkResult(
            ticketId = ticketId.takeIf { it.isNotBlank() },
            url = url,
            created = created,
            success = success,
            errorMessage = errorMessage,
        )
}
