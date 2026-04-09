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
import java.io.Closeable

/**
 * Notion implementation of [TicketBackend].
 *
 * Creates a new Notion page in the specified database for each unique crash.
 *
 * **Public API** — external devs instantiate this and pass it to [MBAMode.SdkOnly]:
 * ```kotlin
 * val notionBackend = NotionTicketBackend(
 *     apiKey = "secret_...",
 *     databaseId = "abc123...",
 * )
 * mode = MBAMode.SdkOnly(llmApiKey = "...", ticketBackend = notionBackend)
 * ```
 *
 * @param apiKey Notion integration token (starts with "secret_").
 * @param databaseId ID of the target Notion database.
 * @param fieldMapping Custom property name mapping (defaults work with Notion's default DB).
 * @param httpClient Optional shared HttpClient. If not provided, creates one internally.
 * @param notionApiVersion Notion API version header.
 */
public class NotionTicketBackend(
    private val apiKey: String,
    private val databaseId: String,
    private val fieldMapping: NotionFieldMapping = NotionFieldMapping(),
    private val httpClient: HttpClient = defaultHttpClient(),
    private val notionApiVersion: String = "2022-06-28",
) : TicketBackend, Closeable {

    override val name: String = "Notion"

    override suspend fun createTicket(report: ProcessedCrashReport): TicketResult {
        return try {
            val response: NotionPageResponse = httpClient.post("https://api.notion.com/v1/pages") {
                header("Authorization", "Bearer $apiKey")
                header("Notion-Version", notionApiVersion)
                contentType(ContentType.Application.Json)
                setBody(mapReportToNotionRequest(report))
            }.body()

            TicketResult(
                ticketId = response.id,
                backendName = name,
                url = response.url,
                success = true,
            )
        } catch (e: Exception) {
            TicketResult.failure(name, e.message ?: "Unknown error creating Notion ticket")
        }
    }

    override suspend fun updateTicket(ticketId: String, update: TicketUpdate): TicketResult {
        return try {
            httpClient.patch("https://api.notion.com/v1/pages/$ticketId") {
                header("Authorization", "Bearer $apiKey")
                header("Notion-Version", notionApiVersion)
                contentType(ContentType.Application.Json)
                // Build update body based on TicketUpdate fields
                val properties = mutableMapOf<String, NotionProperty>()
                update.addDevice?.let { device ->
                    properties[fieldMapping.device] = NotionProperty.RichText(
                        listOf(NotionRichText(text = NotionTextContent(device.displayName)))
                    )
                }
                setBody(mapOf("properties" to properties))
            }
            TicketResult(ticketId = ticketId, backendName = name, success = true)
        } catch (e: Exception) {
            TicketResult.failure(name, e.message ?: "Unknown error updating Notion ticket")
        }
    }

    /** Release the HttpClient if it was created internally. */
    override fun close() {
        httpClient.close()
    }

    private fun mapReportToNotionRequest(report: ProcessedCrashReport): NotionPageRequest {
        val properties = mutableMapOf<String, NotionProperty>()

        // Title
        properties[fieldMapping.title] = NotionProperty.Title(
            listOf(NotionRichText(text = NotionTextContent(report.title)))
        )
        // Severity (Select)
        properties[fieldMapping.severity] = NotionProperty.Select(
            NotionSelectItem(name = report.severity.name)
        )
        // Fingerprint (Rich Text)
        properties[fieldMapping.fingerprint] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.fingerprint)))
        )
        // Device (Rich Text)
        properties[fieldMapping.device] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.raw.device.displayName)))
        )
        // Root Cause (Rich Text)
        properties[fieldMapping.rootCause] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.possibleCause ?: "Unknown")))
        )

        // Content blocks
        val children = mutableListOf<NotionBlock>()

        // Description paragraph
        children.add(NotionBlock(
            type = "paragraph",
            paragraph = NotionParagraphBlock(
                listOf(NotionRichText(text = NotionTextContent(report.description)))
            )
        ))
        // Stack trace code block
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
            children = children,
        )
    }

    public companion object {
        private fun defaultHttpClient(): HttpClient = HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                })
            }
        }
    }
}

/**
 * Configurable mapping from MBA fields to Notion database property names.
 *
 * Defaults match a standard Notion database with properties:
 * Name, Severity, Fingerprint, Device, Root Cause.
 *
 * Override if your Notion DB uses different property names:
 * ```kotlin
 * NotionTicketBackend(
 *     apiKey = "...",
 *     databaseId = "...",
 *     fieldMapping = NotionFieldMapping(title = "Bug Title", severity = "Priority"),
 * )
 * ```
 */
public data class NotionFieldMapping(
    val title: String = "Name",
    val severity: String = "Severity",
    val fingerprint: String = "Fingerprint",
    val device: String = "Device",
    val rootCause: String = "Root Cause",
)
