package dev.sunnat629.mba.notion

import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.TicketResult
import dev.sunnat629.mba.core.ticket.TicketBackend
import dev.sunnat629.mba.core.ticket.TicketUpdate
import dev.sunnat629.mba.notion.model.NotionCreatePageRequest
import dev.sunnat629.mba.notion.model.NotionProperty

/**
 * MVP Notion TicketBackend.
 *
 * Assumptions:
 * - The target database has properties with the following names:
 *   - Name (title)
 *   - Severity (select) OR Severity (rich text) depending on your DB
 *   - Fingerprint (rich text)
 *   - Description (rich text)
 */
class NotionTicketBackend(
    private val notion: NotionClient,
    private val config: NotionConfig,
    private val propertyTitle: String = "Name",
    private val propertySeverity: String = "Severity",
    private val propertyFingerprint: String = "Fingerprint",
    private val propertyDescription: String = "Description",
) : TicketBackend {

    override val name: String = "Notion"

    override suspend fun createTicket(report: ProcessedCrashReport): TicketResult {
        val props = linkedMapOf(
            propertyTitle to NotionProperty.title(report.title),
            propertyDescription to NotionProperty.richText(report.description),
            propertyFingerprint to NotionProperty.richText(report.fingerprint),
            propertySeverity to NotionProperty.select(report.severity.name),
        )

        val resp = notion.createPage(
            NotionCreatePageRequest(
                parent = NotionCreatePageRequest.Parent(databaseId = config.databaseId),
                properties = props,
            )
        )

        return TicketResult(
            ticketId = resp.id,
            url = resp.url,
            backend = name,
        )
    }

    override suspend fun updateTicket(ticketId: String, update: TicketUpdate): TicketResult {
        // MVP: not implemented (Notion update endpoint wiring can be added when DB schema is finalized)
        return TicketResult(
            ticketId = ticketId,
            url = null,
            backend = name,
        )
    }
}
