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
                    explicitNulls = false
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

            if (!httpResponse.status.isSuccess()) {
                val errorBody = httpResponse.bodyAsText()
                return TicketResult.failure(name, "Notion API ${httpResponse.status}: $errorBody")
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
                    properties[fieldMapping.affectedDevices] = NotionProperty.RichText(
                        listOf(NotionRichText(text = NotionTextContent(device.displayName)))
                    )
                }
                setBody(mapOf("properties" to properties))
            }

            if (!httpResponse.status.isSuccess()) {
                val errorBody = httpResponse.bodyAsText()
                return TicketResult.failure(name, "Notion API ${httpResponse.status}: $errorBody")
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

        // Title (title property)
        properties[fieldMapping.title] = NotionProperty.Title(
            listOf(NotionRichText(text = NotionTextContent(report.title)))
        )

        // Severity (select)
        properties[fieldMapping.severity] = NotionProperty.Select(
            NotionSelectItem(name = report.severity.name)
        )

        // Fingerprint (text)
        properties[fieldMapping.fingerprint] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.fingerprint)))
        )

        // Affected Devices (text)
        properties[fieldMapping.affectedDevices] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.raw.device.displayName)))
        )

        // Stack Trace (text)
        properties[fieldMapping.stackTrace] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(
                report.sanitizedStackTrace.take(2000) // Notion text limit
            )))
        )

        // Exception Type (select)
        val exceptionShort = report.raw.exceptionType
            .substringAfterLast(".")
            .let { type ->
                // Map to one of the DB's select options
                when {
                    type.contains("NullPointer") -> "NullPointerException"
                    type.contains("IllegalState") -> "IllegalStateException"
                    type.contains("OutOfMemory") -> "OutOfMemoryError"
                    type.contains("Security") -> "SecurityException"
                    else -> "Other"
                }
            }
        properties[fieldMapping.exceptionType] = NotionProperty.Select(
            NotionSelectItem(name = exceptionShort)
        )

        // Crash File (text)
        report.crashFile?.let { file ->
            properties[fieldMapping.crashFile] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent(file)))
            )
        }

        // Crash Line (number)
        report.crashLine?.let { line ->
            properties[fieldMapping.crashLine] = NotionProperty.Number(line.toDouble())
        }

        // AI Confidence (number — percent format)
        properties[fieldMapping.aiConfidence] = NotionProperty.Number(
            report.confidence.toDouble()
        )

        // App Version (text)
        properties[fieldMapping.appVersion] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.raw.appVersion)))
        )

        // OS Versions (text)
        properties[fieldMapping.osVersions] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(
                "Android ${report.raw.device.osVersion} (API ${report.raw.device.sdkInt})"
            )))
        )

        // Occurrence Count (number)
        properties[fieldMapping.occurrenceCount] = NotionProperty.Number(1.0)

        // Status (status) — new crashes start as "New"
        // Note: Status properties use a different API format, skip for now

        // Content blocks (page body)
        val children = mutableListOf<NotionBlock>()

        // Description paragraph
        children.add(NotionBlock(
            type = "paragraph",
            paragraph = NotionParagraphBlock(
                listOf(NotionRichText(text = NotionTextContent(report.description)))
            )
        ))

        // Possible cause
        report.possibleCause?.let { cause ->
            children.add(NotionBlock(
                type = "paragraph",
                paragraph = NotionParagraphBlock(
                    listOf(NotionRichText(text = NotionTextContent("Possible cause: $cause")))
                )
            ))
        }

        // Steps to reproduce
        report.stepsToReproduce?.let { steps ->
            children.add(NotionBlock(
                type = "paragraph",
                paragraph = NotionParagraphBlock(
                    listOf(NotionRichText(text = NotionTextContent("Steps to reproduce:\n$steps")))
                )
            ))
        }

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
}

/**
 * Mapping from MBA fields to Notion database property names.
 *
 * Defaults match the MBA Crash Reports DB schema:
 *   Title, Severity, Fingerprint, Affected Devices, Stack Trace,
 *   Exception Type, Crash File, Crash Line, AI Confidence,
 *   App Version, OS Versions, Occurrence Count
 */
public data class NotionFieldMapping(
    val title: String = "Title",
    val severity: String = "Severity",
    val fingerprint: String = "Fingerprint",
    val affectedDevices: String = "Affected Devices",
    val stackTrace: String = "Stack Trace",
    val exceptionType: String = "Exception Type",
    val crashFile: String = "Crash File",
    val crashLine: String = "Crash Line",
    val aiConfidence: String = "AI Confidence",
    val appVersion: String = "App Version",
    val osVersions: String = "OS Versions",
    val occurrenceCount: String = "Occurrence Count",
)
