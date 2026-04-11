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
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

public class NotionTicketBackend(
    private val apiKey: String,
    private val databaseId: String,
    private val fieldMapping: NotionFieldMapping = NotionFieldMapping(),
    private val httpClient: HttpClient = defaultHttpClient(),
    private val notionApiVersion: String = "2022-06-28",
) : TicketBackend, AutoCloseable {

    private companion object {
        private fun defaultHttpClient(): HttpClient = HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                })
            }
        }
    }

    override val name: String = "Notion"

    override suspend fun createTicket(report: ProcessedCrashReport): TicketResult {
        return try {
            val httpResponse = httpClient.post("https://api.notion.com/v1/pages") {
                header("Authorization", "Bearer $apiKey")
                header("Notion-Version", notionApiVersion)
                contentType(ContentType.Application.Json)
                setBody(mapReportToNotionRequest(report))
            }

            // Check HTTP status BEFORE trying to deserialize
            if (!httpResponse.status.isSuccess()) {
                val errorBody = httpResponse.bodyAsText()
                return TicketResult.failure(
                    name,
                    "Notion API ${httpResponse.status}: $errorBody"
                )
            }

            val response: NotionPageResponse = httpResponse.body()
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
            val httpResponse = httpClient.patch("https://api.notion.com/v1/pages/$ticketId") {
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

            if (!httpResponse.status.isSuccess()) {
                val errorBody = httpResponse.bodyAsText()
                return TicketResult.failure(
                    name,
                    "Notion API ${httpResponse.status}: $errorBody"
                )
            }

            TicketResult(ticketId = ticketId, backendName = name, success = true)
        } catch (e: Exception) {
            TicketResult.failure(name, e.message ?: "Unknown error updating Notion ticket")
        }
    }

    override fun close() {
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

        return NotionPageRequest(
            parent = NotionParent(databaseId),
            properties = properties,
            children = children,
        )
    }
}

public data class NotionFieldMapping(
    val title: String = "Name",
    val severity: String = "Severity",
    val fingerprint: String = "Fingerprint",
    val device: String = "Device",
    val rootCause: String = "Root Cause",
)
