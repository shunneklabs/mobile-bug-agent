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

/**
 * Notion implementation of [TicketBackend].
 *
 * Dual-database strategy:
 * - **Bug Tickets DB** (`bugTicketDbId`): ALL issues go here.
 * - **Crash Reports DB** (`crashReportDbId`): Only crash-related issues (optional).
 * - The two are linked via the Crash Report ↔ Bug Ticket relation.
 *
 * @param apiKey Notion integration token.
 * @param bugTicketDbId Required. Database ID for bug tickets (all issues).
 * @param crashReportDbId Optional. Database ID for crash reports (crash-only).
 */
public class NotionTicketBackend(
    private val apiKey: String,
    private val bugTicketDbId: String,
    private val crashReportDbId: String? = null,
    private val httpClient: HttpClient = defaultHttpClient(),
    private val notionApiVersion: String = "2022-06-28",
) : TicketBackend, AutoCloseable {

    // Legacy constructor for backward compat
    public constructor(
        apiKey: String,
        databaseId: String,
    ) : this(apiKey = apiKey, bugTicketDbId = databaseId)

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
            // 1. Always create Bug Ticket
            val bugTicketResult = createBugTicket(report)
            if (!bugTicketResult.success) return bugTicketResult

            // 2. If crash-related AND crashReportDbId is configured, also create Crash Report
            var crashReportPageId: String? = null
            if (crashReportDbId != null && report.raw.isFatal) {
                val crashResult = createCrashReport(report, bugTicketPageUrl = bugTicketResult.ticketId)
                crashReportPageId = if (crashResult.success) crashResult.ticketId else null
            } else if (crashReportDbId != null && report.sanitizedStackTrace.isNotBlank()) {
                // Non-fatal but has a stack trace — also a crash report
                val crashResult = createCrashReport(report, bugTicketPageUrl = bugTicketResult.ticketId)
                crashReportPageId = if (crashResult.success) crashResult.ticketId else null
            }

            // 3. If crash report was created, link it back to the bug ticket
            if (crashReportPageId != null) {
                linkCrashReportToBugTicket(bugTicketResult.ticketId, crashReportPageId)
            }

            bugTicketResult
        } catch (e: Exception) {
            TicketResult.failure(name, e.message ?: "Unknown error")
        }
    }

    // ================================================================ //
    //  Bug Tickets DB (ALL issues)
    // ================================================================ //

    private suspend fun createBugTicket(report: ProcessedCrashReport): TicketResult {
        val properties = mutableMapOf<String, NotionProperty>()

        // Name (title)
        properties["Name"] = NotionProperty.Title(
            listOf(NotionRichText(text = NotionTextContent(report.title)))
        )
        // Severity (select: CRITICAL, HIGH, MEDIUM, LOW)
        properties["Severity"] = NotionProperty.Select(
            NotionSelectItem(name = report.severity.name)
        )
        // Fingerprint (text)
        properties["Fingerprint"] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.fingerprint)))
        )
        // Description (text)
        properties["Description"] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.description.take(2000))))
        )
        // Affected Screen (text)
        report.raw.currentScreen?.let { screen ->
            properties["Affected Screen"] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent(screen)))
            )
        }
        // Device Matrix (text)
        properties["Device Matrix"] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.raw.device.displayName)))
        )
        // AI Confidence (number, percent)
        properties["AI Confidence"] = NotionProperty.Number(report.confidence.toDouble())
        // App Version (text)
        properties["App Version"] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.raw.appVersion)))
        )
        // Occurrences (number)
        properties["Occurrences"] = NotionProperty.Number(1.0)
        // Possible Cause (text)
        report.possibleCause?.let { cause ->
            properties["Possible Cause"] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent(cause.take(2000))))
            )
        }
        // Steps to Reproduce (text)
        report.stepsToReproduce?.let { steps ->
            properties["Steps to Reproduce"] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent(steps.take(2000))))
            )
        }

        // Page body content
        val children = mutableListOf<NotionBlock>()
        children.add(NotionBlock(
            type = "paragraph",
            paragraph = NotionParagraphBlock(
                listOf(NotionRichText(text = NotionTextContent(report.description)))
            )
        ))
        if (report.sanitizedStackTrace.isNotBlank()) {
            children.add(NotionBlock(
                type = "code",
                code = NotionCodeBlock(
                    rich_text = listOf(NotionRichText(text = NotionTextContent(report.sanitizedStackTrace.take(2000)))),
                    language = "kotlin"
                )
            ))
        }

        return postNotionPage(bugTicketDbId, properties, children)
    }

    // ================================================================ //
    //  Crash Reports DB (crash-only)
    // ================================================================ //

    private suspend fun createCrashReport(
        report: ProcessedCrashReport,
        bugTicketPageUrl: String,
    ): TicketResult {
        val dbId = crashReportDbId ?: return TicketResult.failure(name, "No crash report DB configured")

        val properties = mutableMapOf<String, NotionProperty>()

        // Title (title)
        properties["Title"] = NotionProperty.Title(
            listOf(NotionRichText(text = NotionTextContent(report.title)))
        )
        // Severity (select)
        properties["Severity"] = NotionProperty.Select(
            NotionSelectItem(name = report.severity.name)
        )
        // Fingerprint (text)
        properties["Fingerprint"] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.fingerprint)))
        )
        // Stack Trace (text)
        properties["Stack Trace"] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.sanitizedStackTrace.take(2000))))
        )
        // Exception Type (select)
        val exceptionShort = report.raw.exceptionType.substringAfterLast(".").let { type ->
            when {
                type.contains("NullPointer") -> "NullPointerException"
                type.contains("IllegalState") -> "IllegalStateException"
                type.contains("OutOfMemory") -> "OutOfMemoryError"
                type.contains("Security") -> "SecurityException"
                else -> "Other"
            }
        }
        properties["Exception Type"] = NotionProperty.Select(NotionSelectItem(name = exceptionShort))
        // Affected Devices (text)
        properties["Affected Devices"] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.raw.device.displayName)))
        )
        // Crash File (text)
        report.crashFile?.let {
            properties["Crash File"] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent(it)))
            )
        }
        // Crash Line (number)
        report.crashLine?.let {
            properties["Crash Line"] = NotionProperty.Number(it.toDouble())
        }
        // AI Confidence (number, percent)
        properties["AI Confidence"] = NotionProperty.Number(report.confidence.toDouble())
        // App Version (text)
        properties["App Version"] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.raw.appVersion)))
        )
        // OS Versions (text)
        properties["OS Versions"] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(
                "Android ${report.raw.device.osVersion} (API ${report.raw.device.sdkInt})"
            )))
        )
        // Occurrence Count (number)
        properties["Occurrence Count"] = NotionProperty.Number(1.0)

        // Page body
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
                rich_text = listOf(NotionRichText(text = NotionTextContent(report.sanitizedStackTrace.take(2000)))),
                language = "kotlin"
            )
        ))

        return postNotionPage(dbId, properties, children)
    }

    // ================================================================ //
    //  Link Crash Report ↔ Bug Ticket via relation
    // ================================================================ //

    private suspend fun linkCrashReportToBugTicket(bugTicketPageId: String, crashReportPageId: String) {
        try {
            // Update Bug Ticket: set "Crash Report" relation to the crash report page
            httpClient.patch("https://api.notion.com/v1/pages/$bugTicketPageId") {
                header("Authorization", "Bearer $apiKey")
                header("Notion-Version", notionApiVersion)
                contentType(ContentType.Application.Json)
                setBody(mapOf(
                    "properties" to mapOf(
                        "Crash Report" to mapOf(
                            "relation" to listOf(mapOf("id" to crashReportPageId))
                        )
                    )
                ))
            }
        } catch (_: Exception) {
            // Best effort — don't fail the whole ticket creation if linking fails
        }
    }

    // ================================================================ //
    //  Shared: POST to Notion API
    // ================================================================ //

    private suspend fun postNotionPage(
        databaseId: String,
        properties: Map<String, NotionProperty>,
        children: List<NotionBlock>,
    ): TicketResult {
        val httpResponse = httpClient.post("https://api.notion.com/v1/pages") {
            header("Authorization", "Bearer $apiKey")
            header("Notion-Version", notionApiVersion)
            contentType(ContentType.Application.Json)
            setBody(NotionPageRequest(
                parent = NotionParent(databaseId),
                properties = properties,
                children = children,
            ))
        }

        if (!httpResponse.status.isSuccess()) {
            val errorBody = httpResponse.bodyAsText()
            return TicketResult.failure(name, "Notion API ${httpResponse.status}: $errorBody")
        }

        val response: NotionPageResponse = httpResponse.body()
        return TicketResult(
            ticketId = response.id,
            backendName = name,
            url = response.url,
            success = true,
        )
    }

    override suspend fun updateTicket(ticketId: String, update: TicketUpdate): TicketResult {
        return try {
            val httpResponse = httpClient.patch("https://api.notion.com/v1/pages/$ticketId") {
                header("Authorization", "Bearer $apiKey")
                header("Notion-Version", notionApiVersion)
                contentType(ContentType.Application.Json)
                val properties = mutableMapOf<String, NotionProperty>()
                update.addDevice?.let { device ->
                    properties["Device Matrix"] = NotionProperty.RichText(
                        listOf(NotionRichText(text = NotionTextContent(device.displayName)))
                    )
                }
                setBody(mapOf("properties" to properties))
            }
            if (!httpResponse.status.isSuccess()) {
                return TicketResult.failure(name, "Notion API ${httpResponse.status}: ${httpResponse.bodyAsText()}")
            }
            TicketResult(ticketId = ticketId, backendName = name, success = true)
        } catch (e: Exception) {
            TicketResult.failure(name, e.message ?: "Unknown error")
        }
    }

    override fun close() {
        httpClient.close()
    }
}
