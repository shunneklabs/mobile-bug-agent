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

    private val databaseSchemaCache = mutableMapOf<String, Map<String, NotionDatabaseProperty>>()

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
        properties["Occurred At"] = NotionProperty.Date(NotionDate(report.raw.timestamp.toString()))
        properties["OS Version"] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent("Android ${report.raw.device.osVersion} (API ${report.raw.device.sdkInt})")))
        )
        properties["Device Model"] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.raw.device.displayName)))
        )
        // Occurrences (number)
        properties["Occurrences"] = NotionProperty.Number(1.0)
        properties["Unique Devices"] = NotionProperty.Number(1.0)
        properties["Bug Type"] = NotionProperty.Select(NotionSelectItem(name = "Bug Group"))
        properties["Last Seen"] = NotionProperty.Date(NotionDate(report.raw.timestamp.toString()))
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
        val children = buildCrashBody(report)
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

    public suspend fun createCrashOccurrence(
        report: ProcessedCrashReport,
        parentBugPageId: String,
    ): TicketResult {
        return try {
            val properties = mutableMapOf<String, NotionProperty>()
            properties["Name"] = NotionProperty.Title(
                listOf(NotionRichText(text = NotionTextContent("${report.raw.exceptionType.substringAfterLast(".")} occurrence")))
            )
            properties["Bug Type"] = NotionProperty.Select(NotionSelectItem(name = "Crash Occurrence"))
            properties["Affected Screen"] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent(report.raw.currentScreen ?: "unknown")))
            )
            properties["Device Matrix"] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent(report.raw.device.displayName)))
            )
            properties["App Version"] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent(report.raw.appVersion)))
            )
            properties["Occurred At"] = NotionProperty.Date(NotionDate(report.raw.timestamp.toString()))
            properties["OS Version"] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent("Android ${report.raw.device.osVersion} (API ${report.raw.device.sdkInt})")))
            )
            properties["Device Model"] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent(report.raw.device.displayName)))
            )
            properties["Last Seen"] = NotionProperty.Date(NotionDate(report.raw.timestamp.toString()))
            properties["Parent Bug"] = NotionProperty.Relation(listOf(NotionRelationItem(parentBugPageId)))

            val children = listOf(
                NotionBlock(
                    type = "paragraph",
                    paragraph = NotionParagraphBlock(
                        listOf(NotionRichText(text = NotionTextContent(
                            buildString {
                                appendLine("Repeated occurrence of parent bug.")
                                appendLine("Occurred At: ${report.raw.timestamp}")
                                appendLine("Device Model: ${report.raw.device.displayName}")
                                appendLine("OS Version: Android ${report.raw.device.osVersion} (API ${report.raw.device.sdkInt})")
                                appendLine("App Version: ${report.raw.appVersion}")
                                report.raw.currentScreen?.let { appendLine("Screen: $it") }
                            }.take(2000)
                        )))
                    )
                )
            )
            postNotionPage(bugTicketDbId, properties, children)
        } catch (e: Exception) {
            TicketResult.failure(name, e.message ?: "Unknown error")
        }
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
        properties["Occurred At"] = NotionProperty.Date(NotionDate(report.raw.timestamp.toString()))
        properties["Device Model"] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.raw.device.displayName)))
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
        val children = buildCrashBody(report)
        children.add(NotionBlock(
            type = "code",
            code = NotionCodeBlock(
                rich_text = listOf(NotionRichText(text = NotionTextContent(report.sanitizedStackTrace.take(2000)))),
                language = "kotlin"
            )
        ))

        return postNotionPage(dbId, properties, children)
    }

    private fun buildCrashBody(report: ProcessedCrashReport): MutableList<NotionBlock> {
        val lines = buildList {
            add("Description: ${report.description}")
            add("Occurred At: ${report.raw.timestamp}")
            add("Fingerprint: ${report.fingerprint}")
            add("Severity: ${report.severity} (${(report.confidence * 100).toInt()}% confidence)")
            add("Exception: ${report.raw.exceptionType}")
            report.raw.message?.let { add("Message: $it") }
            add("Device Model: ${report.raw.device.displayName}")
            add("OS Version: Android ${report.raw.device.osVersion} (API ${report.raw.device.sdkInt})")
            add("App Version: ${report.raw.appVersion}")
            add("Build Type: ${report.raw.buildType}")
            report.raw.currentScreen?.let { add("Affected Screen: $it") }
            report.crashFile?.let { file ->
                add("Location: $file${report.crashLine?.let { ":$it" } ?: ""}${report.crashMethod?.let { " in $it" } ?: ""}")
            }
            report.possibleCause?.let { add("Possible Cause: $it") }
            report.stepsToReproduce?.let { add("Steps to Reproduce: $it") }
            if (report.raw.breadcrumbs.isNotEmpty()) {
                add("Breadcrumbs: ${report.raw.breadcrumbs.joinToString(" -> ")}")
            }
        }
        return mutableListOf(
            NotionBlock(
                type = "paragraph",
                paragraph = NotionParagraphBlock(
                    listOf(NotionRichText(text = NotionTextContent(lines.joinToString("\n").take(2000))))
                )
            )
        )
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
        val firstResponse = postPage(databaseId, properties, children)
        if (firstResponse.status.isSuccess()) {
            val response: NotionPageResponse = firstResponse.body()
            return TicketResult(
                ticketId = response.id,
                backendName = name,
                url = response.url,
                success = true,
            )
        }

        val firstErrorBody = firstResponse.bodyAsText()
        if (!firstResponse.isSchemaMismatch(firstErrorBody)) {
            return TicketResult.failure(name, "Notion API ${firstResponse.status}: $firstErrorBody")
        }

        val schema = fetchDatabaseSchema(databaseId)
            ?: return TicketResult.failure(name, "Notion API ${firstResponse.status}: $firstErrorBody")
        val compatibleProperties = properties.compatibleWith(schema)
        if (compatibleProperties.isEmpty()) {
            return TicketResult.failure(name, "Notion API ${firstResponse.status}: $firstErrorBody")
        }

        val retryResponse = postPage(databaseId, compatibleProperties, children)
        if (!retryResponse.status.isSuccess()) {
            return TicketResult.failure(name, "Notion API ${retryResponse.status}: ${retryResponse.bodyAsText()}")
        }

        val response: NotionPageResponse = retryResponse.body()
        return TicketResult(
            ticketId = response.id,
            backendName = name,
            url = response.url,
            success = true,
        )
    }

    private suspend fun postPage(
        databaseId: String,
        properties: Map<String, NotionProperty>,
        children: List<NotionBlock>,
    ): HttpResponse =
        httpClient.post("https://api.notion.com/v1/pages") {
            header("Authorization", "Bearer $apiKey")
            header("Notion-Version", notionApiVersion)
            contentType(ContentType.Application.Json)
            setBody(NotionPageRequest(
                parent = NotionParent(databaseId),
                properties = properties,
                children = children,
            ))
        }

    private fun HttpResponse.isSchemaMismatch(body: String): Boolean =
        status == HttpStatusCode.BadRequest &&
            body.contains("validation_error") &&
            body.contains("is not a property that exists")

    private suspend fun fetchDatabaseSchema(databaseId: String): Map<String, NotionDatabaseProperty>? {
        databaseSchemaCache[databaseId]?.let { return it }
        return try {
            val response = httpClient.get("https://api.notion.com/v1/databases/$databaseId") {
                header("Authorization", "Bearer $apiKey")
                header("Notion-Version", notionApiVersion)
            }
            if (!response.status.isSuccess()) return null
            val database: NotionDatabaseResponse = response.body()
            databaseSchemaCache[databaseId] = database.properties
            database.properties
        } catch (_: Exception) {
            null
        }
    }

    private fun Map<String, NotionProperty>.compatibleWith(
        schema: Map<String, NotionDatabaseProperty>,
    ): Map<String, NotionProperty> {
        val titleProperty = schema.entries.firstOrNull { it.value.type == "title" }?.key
        val remapped = linkedMapOf<String, NotionProperty>()
        for ((name, property) in this) {
            val targetName = when {
                schema[name]?.type == property.notionType -> name
                property is NotionProperty.Title && titleProperty != null -> titleProperty
                else -> null
            } ?: continue
            if (schema[targetName]?.type == property.notionType) {
                remapped[targetName] = property
            }
        }
        return remapped
    }

    private val NotionProperty.notionType: String
        get() = when (this) {
            is NotionProperty.Title -> "title"
            is NotionProperty.RichText -> "rich_text"
            is NotionProperty.Select -> "select"
            is NotionProperty.Number -> "number"
            is NotionProperty.Date -> "date"
            is NotionProperty.Relation -> "relation"
        }

    override suspend fun updateTicket(ticketId: String, update: TicketUpdate): TicketResult {
        return try {
            val properties = buildUpdateProperties(update)
            val httpResponse = patchPage(ticketId, properties)
            if (!httpResponse.status.isSuccess()) {
                val errorBody = httpResponse.bodyAsText()
                if (!httpResponse.isSchemaMismatch(errorBody)) {
                    return TicketResult.failure(name, "Notion API ${httpResponse.status}: $errorBody")
                }
                return TicketResult(ticketId = ticketId, backendName = name, success = true)
            }
            TicketResult(ticketId = ticketId, backendName = name, success = true)
        } catch (e: Exception) {
            TicketResult.failure(name, e.message ?: "Unknown error")
        }
    }

    private fun buildUpdateProperties(update: TicketUpdate): Map<String, NotionProperty> {
        val properties = mutableMapOf<String, NotionProperty>()
        update.addDevice?.let { device ->
            properties["Device Matrix"] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent(device.displayName)))
            )
        }
        update.occurrenceCount?.let { count ->
            properties["Occurrences"] = NotionProperty.Number(count.toDouble())
        }
        update.uniqueDeviceCount?.let { count ->
            properties["Unique Devices"] = NotionProperty.Number(count.toDouble())
        }
        update.deviceMatrix?.let { matrix ->
            properties["Device Matrix"] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent(matrix.take(2000))))
            )
        }
        update.newOccurrenceTime?.let { time ->
            properties["Last Seen"] = NotionProperty.Date(NotionDate(time.toString()))
            properties["Occurred At"] = NotionProperty.Date(NotionDate(time.toString()))
        }
        update.githubIssueUrl?.let { url ->
            properties["GitHub Issue"] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent(url)))
            )
        }
        update.notionUrl?.let { url ->
            properties["Notion URL"] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent(url)))
            )
        }
        return properties
    }

    private suspend fun patchPage(ticketId: String, properties: Map<String, NotionProperty>): HttpResponse =
        httpClient.patch("https://api.notion.com/v1/pages/$ticketId") {
            header("Authorization", "Bearer $apiKey")
            header("Notion-Version", notionApiVersion)
            contentType(ContentType.Application.Json)
            setBody(mapOf("properties" to properties))
        }

    override fun close() {
        httpClient.close()
    }
}
