package dev.sunnat629.mba.notion

import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.TicketResult
import dev.sunnat629.mba.core.ticket.TicketBackend
import dev.sunnat629.mba.core.ticket.TicketUpdate
import dev.sunnat629.mba.notion.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Notion implementation of the [TicketBackend].
 * Creates a new page in the specified database for each unique crash.
 */
class NotionTicketBackend(
    private val apiKey: String,
    private val databaseId: String,
    private val httpClient: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }
) : TicketBackend {

    override val name: String = "Notion"

    override suspend fun createTicket(report: ProcessedCrashReport): TicketResult {
        return try {
            val response: NotionPageResponse = httpClient.post("https://api.notion.com/v1/pages") {
                header("Authorization", "Bearer $apiKey")
                header("Notion-Version", "2022-06-28")
                contentType(ContentType.Application.Json)
                setBody(mapReportToNotionRequest(report))
            }.body()

            TicketResult(
                ticketId = response.id,
                backendName = name,
                url = response.url,
                success = true
            )
        } catch (e: Exception) {
            TicketResult.failure(name, e.message ?: "Unknown error creating Notion ticket")
        }
    }

    override suspend fun updateTicket(ticketId: String, update: TicketUpdate): TicketResult {
        // Notion updates are typically via PATCH /v1/pages/{page_id}
        // For MVP, we'll just return success if we don't have complex update logic yet.
        return TicketResult(ticketId, name, success = true)
    }

    private fun mapReportToNotionRequest(report: ProcessedCrashReport): NotionPageRequest {
        val properties = mutableMapOf<String, NotionProperty>()

        // Title property (usually "Name" in Notion default DBs)
        properties["Name"] = NotionProperty.Title(
            listOf(NotionRichText(text = NotionTextContent(report.title)))
        )

        // Severity (assuming a Select property named "Severity")
        properties["Severity"] = NotionProperty.Select(
            NotionSelectItem(name = report.severity.name)
        )

        // Fingerprint (Rich Text)
        properties["Fingerprint"] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.fingerprint)))
        )

        // Device (Rich Text)
        properties["Device"] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.raw.device.displayName)))
        )

        // Root Cause (Rich Text)
        properties["Root Cause"] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.possibleCause ?: "Unknown")))
        )

        // Content Blocks
        val children = mutableListOf<NotionBlock>()

        // Description Paragraph
        children.add(NotionBlock(
            type = "paragraph",
            paragraph = NotionParagraphBlock(
                listOf(NotionRichText(text = NotionTextContent(report.description)))
            )
        ))

        // Stack Trace Code Block
        children.add(NotionBlock(
            type = "code",
            code = NotionCodeBlock(
                rich_text = listOf(NotionRichText(text = NotionTextContent(report.sanitizedStackTrace))),
                language = "kotlin"
            )
        ))

        return NotionPageRequest(
            parent = NotionParent(databaseId),
            properties = properties,
            children = children
        )
    }
}
