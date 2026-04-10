package dev.sunnat629.mba.notion

import dev.sunnat629.mba.core.MBALog
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

public class NotionTicketBackend(
    private val apiKey: String,
    private val databaseId: String,
    private val fieldMapping: NotionFieldMapping = NotionFieldMapping(),
    private val httpClient: HttpClient = defaultHttpClient(),
    private val notionApiVersion: String = "2022-06-28",
) : TicketBackend, Closeable {

    private companion object {
        const val TAG = "Notion"
    }

    override val name: String = "Notion"

    override suspend fun createTicket(report: ProcessedCrashReport): TicketResult {
        MBALog.i(TAG, "Creating ticket: '${report.title}' [${report.severity}]")
        return try {
            val response: NotionPageResponse = httpClient.post("https://api.notion.com/v1/pages") {
                header("Authorization", "Bearer $apiKey")
                header("Notion-Version", notionApiVersion)
                contentType(ContentType.Application.Json)
                setBody(mapReportToNotionRequest(report))
            }.body()

            MBALog.i(TAG, "\u2705 Ticket created: id=${response.id}, url=${response.url}")
            TicketResult(
                ticketId = response.id,
                backendName = name,
                url = response.url,
                success = true,
            )
        } catch (e: Exception) {
            MBALog.e(TAG, "\u274c Failed to create ticket: ${e.message}", e)
            TicketResult.failure(name, e.message ?: "Unknown error creating Notion ticket")
        }
    }

    override suspend fun updateTicket(ticketId: String, update: TicketUpdate): TicketResult {
        MBALog.d(TAG, "Updating ticket: $ticketId")
        return try {
            httpClient.patch("https://api.notion.com/v1/pages/$ticketId") {
                header("Authorization", "Bearer $apiKey")
                header("Notion-Version", notionApiVersion)
                contentType(ContentType.Application.Json)
                val properties = mutableMapOf<String, NotionProperty>()
                update.addDevice?.let { device ->
                    properties[fieldMapping.device] = NotionProperty.RichText(
                        listOf(NotionRichText(text = NotionTextContent(device.displayName)))
                    )
                }
                setBody(mapOf("properties" to properties))
            }
            MBALog.i(TAG, "\u2705 Ticket updated: $ticketId")
            TicketResult(ticketId = ticketId, backendName = name, success = true)
        } catch (e: Exception) {
            MBALog.e(TAG, "\u274c Failed to update ticket $ticketId: ${e.message}", e)
            TicketResult.failure(name, e.message ?: "Unknown error updating Notion ticket")
        }
    }

    override fun close() {
        MBALog.d(TAG, "Closing HttpClient")
        httpClient.close()
    }

    private fun mapReportToNotionRequest(report: ProcessedCrashReport): NotionPageRequest {
        val properties = mutableMapOf<String, NotionProperty>()
        properties[fieldMapping.title] = NotionProperty.Title(
            listOf(NotionRichText(text = NotionTextContent(report.title)))
        )
        properties[fieldMapping.severity] = NotionProperty.Select(
            NotionSelectItem(name = report.severity.name)
        )
        properties[fieldMapping.fingerprint] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.fingerprint)))
        )
        properties[fieldMapping.device] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.raw.device.displayName)))
        )
        properties[fieldMapping.rootCause] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.possibleCause ?: "Unknown")))
        )

        val children = mutableListOf<NotionBlock>()
        children.add(NotionBlock(
            type = "paragraph",
            paragraph = NotionParagraphBlock(
                listOf(NotionRichText(text = NotionTextContent(report.description)))
            )
        ))
        children.add(NotionBlock(
            type = "code",
            code = NotionCodeBlock(
                rich_text = listOf(NotionRichText(text = NotionTextContent(report.sanitizedStackTrace))),
                language = "kotlin"
            )
        ))

        MBALog.d(TAG, "Built Notion request: ${properties.size} properties, ${children.size} content blocks")
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

public data class NotionFieldMapping(
    val title: String = "Name",
    val severity: String = "Severity",
    val fingerprint: String = "Fingerprint",
    val device: String = "Device",
    val rootCause: String = "Root Cause",
)
