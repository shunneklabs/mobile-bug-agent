package dev.sunnat629.mba.notion

import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.TicketResult
import dev.sunnat629.mba.core.ticket.CrashOccurrenceTicketBackend
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
 * Single-database strategy:
 * - **Bug Tickets DB** (`bugTicketDbId`): grouped bugs and linked occurrences.
 *
 * @param apiKey Notion integration token.
 * @param bugTicketDbId Required. Database ID for bug tickets (all issues).
 */
public class NotionTicketBackend(
    private val apiKey: String,
    private val bugTicketDbId: String,
    private val httpClient: HttpClient = defaultHttpClient(),
    private val notionApiVersion: String = "2022-06-28",
) : CrashOccurrenceTicketBackend, AutoCloseable {

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
            createBugTicket(report)
        } catch (e: Exception) {
            TicketResult.failure(name, e.message ?: "Unknown error")
        }
    }

    // ================================================================ //
    //  Bug Tickets DB (ALL issues)
    // ================================================================ //

    private suspend fun createBugTicket(report: ProcessedCrashReport): TicketResult {
        val properties = mutableMapOf<String, NotionProperty>()
        val isRawFallback = report.isRawFallback()
        val description = report.rawSummary()

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
            listOf(NotionRichText(text = NotionTextContent(description.take(2000))))
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
        properties["Status"] = NotionProperty.Status(NotionSelectItem(name = "New"))
        properties["External Sync State"] = NotionProperty.Select(NotionSelectItem(name = "Notion Created"))
        properties["First Seen"] = NotionProperty.Date(NotionDate(report.raw.timestamp.toString()))
        properties["Last Seen"] = NotionProperty.Date(NotionDate(report.raw.timestamp.toString()))
        properties["Device ID Hash"] = NotionProperty.RichText(
            listOf(NotionRichText(text = NotionTextContent(report.deviceHash())))
        )
        if (!isRawFallback) {
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
        }

        // Page body content
        val children = buildCrashBody(report)
        if (report.sanitizedStackTrace.isNotBlank()) {
            children.addStackTrace(report)
        }

        return postNotionPage(bugTicketDbId, properties, children)
    }

    override suspend fun createCrashOccurrence(
        report: ProcessedCrashReport,
        parentBugTicketId: String,
    ): TicketResult {
        return try {
            val rawSummary = report.rawSummary()
            val properties = mutableMapOf<String, NotionProperty>()
            properties["Name"] = NotionProperty.Title(
                listOf(NotionRichText(text = NotionTextContent("${report.raw.exceptionType.substringAfterLast(".")} occurrence")))
            )
            properties["Bug Type"] = NotionProperty.Select(NotionSelectItem(name = "Crash Occurrence"))
            properties["Parent Bug"] = NotionProperty.Relation(listOf(NotionRelationItem(parentBugTicketId)))
            properties["Fingerprint"] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent(report.fingerprint)))
            )
            properties["Description"] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent(rawSummary.take(2000))))
            )
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
            properties["Device ID Hash"] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent(report.deviceHash())))
            )

            val children = mutableListOf(
                NotionBlock(
                    type = "paragraph",
                    paragraph = NotionParagraphBlock(
                        listOf(NotionRichText(text = NotionTextContent(
                            buildString {
                                appendLine("Repeated occurrence of parent bug.")
                                appendLine("Fingerprint: ${report.fingerprint}")
                                appendLine("Occurred At: ${report.raw.timestamp}")
                                appendLine("Exception: ${report.raw.exceptionType}")
                                appendLine("Message: $rawSummary")
                                appendLine("Device Model: ${report.raw.device.displayName}")
                                appendLine("OS Version: Android ${report.raw.device.osVersion} (API ${report.raw.device.sdkInt})")
                                appendLine("App Version: ${report.raw.appVersion}")
                                report.raw.currentScreen?.let { appendLine("Screen: $it") }
                            }.take(2000)
                        )))
                    )
                )
            )
            if (report.sanitizedStackTrace.isNotBlank()) {
                children.addStackTrace(report)
            }
            postNotionPage(bugTicketDbId, properties, children)
        } catch (e: Exception) {
            TicketResult.failure(name, e.message ?: "Unknown error")
        }
    }

    private fun buildCrashBody(report: ProcessedCrashReport): MutableList<NotionBlock> {
        val lines = buildList {
            add("Description: ${report.rawSummary()}")
            add("Occurred At: ${report.raw.timestamp}")
            add("Fingerprint: ${report.fingerprint}")
            if (!report.isRawFallback()) {
                add("Severity: ${report.severity} (${(report.confidence * 100).toInt()}% confidence)")
            }
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
            if (!report.isRawFallback()) {
                report.possibleCause?.let { add("Possible Cause: $it") }
                report.stepsToReproduce?.let { add("Steps to Reproduce: $it") }
            }
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
            is NotionProperty.Url -> "url"
            is NotionProperty.Status -> "status"
            is NotionProperty.Relation -> "relation"
        }

    override suspend fun updateTicket(ticketId: String, update: TicketUpdate): TicketResult {
        return try {
            val page = fetchPage(ticketId)
                ?: return TicketResult.failure(name, "Notion page not found: $ticketId")
            if (page.archived || page.inTrash) {
                return TicketResult.failure(name, "Notion page is archived or in trash: $ticketId")
            }
            val properties = buildUpdateProperties(update)
            val httpResponse = patchPage(ticketId, properties)
            if (!httpResponse.status.isSuccess()) {
                val errorBody = httpResponse.bodyAsText()
                if (!httpResponse.isSchemaMismatch(errorBody)) {
                    return TicketResult.failure(name, "Notion API ${httpResponse.status}: $errorBody")
                }
                val schema = fetchDatabaseSchema(bugTicketDbId)
                    ?: return TicketResult(ticketId = ticketId, backendName = name, success = true)
                val compatibleProperties = properties.compatibleWith(schema)
                if (compatibleProperties.isEmpty()) {
                    return TicketResult(ticketId = ticketId, backendName = name, success = true)
                }
                val retryResponse = patchPage(ticketId, compatibleProperties)
                if (!retryResponse.status.isSuccess()) {
                    return TicketResult.failure(name, "Notion API ${retryResponse.status}: ${retryResponse.bodyAsText()}")
                }
            }
            TicketResult(ticketId = ticketId, backendName = name, success = true)
        } catch (e: Exception) {
            TicketResult.failure(name, e.message ?: "Unknown error")
        }
    }

    private suspend fun fetchPage(ticketId: String): NotionPageResponse? =
        try {
            val response = httpClient.get("https://api.notion.com/v1/pages/$ticketId") {
                header("Authorization", "Bearer $apiKey")
                header("Notion-Version", notionApiVersion)
            }
            if (response.status.isSuccess()) response.body() else null
        } catch (_: Exception) {
            null
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
            properties["GitHub Issue URL"] = NotionProperty.Url(url)
            properties["External Sync State"] = NotionProperty.Select(NotionSelectItem(name = "Both Created"))
        }
        update.notionUrl?.let { url ->
            properties["Notion Ticket URL"] = NotionProperty.Url(url)
            if (update.githubIssueUrl == null) {
                properties["External Sync State"] = NotionProperty.Select(NotionSelectItem(name = "Notion Created"))
            }
        }
        update.report?.let { report ->
            properties["Fingerprint"] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent(report.fingerprint)))
            )
            properties["AI Confidence"] = NotionProperty.Number(report.confidence.toDouble())
            properties["Device ID Hash"] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent(report.deviceHash())))
            )
        }
        update.report?.takeIf { it.confidence > 0.0f }?.let { report ->
            properties["Name"] = NotionProperty.Title(
                listOf(NotionRichText(text = NotionTextContent(report.title)))
            )
            properties["Severity"] = NotionProperty.Select(
                NotionSelectItem(name = report.severity.name)
            )
            properties["Description"] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent(report.description.take(2000))))
            )
            report.possibleCause?.let { cause ->
                properties["Possible Cause"] = NotionProperty.RichText(
                    listOf(NotionRichText(text = NotionTextContent(cause.take(2000))))
                )
            }
            report.stepsToReproduce?.let { steps ->
                properties["Steps to Reproduce"] = NotionProperty.RichText(
                    listOf(NotionRichText(text = NotionTextContent(steps.take(2000))))
                )
            }
            report.raw.currentScreen?.let { screen ->
                properties["Affected Screen"] = NotionProperty.RichText(
                    listOf(NotionRichText(text = NotionTextContent(screen)))
                )
            }
            properties["App Version"] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent(report.raw.appVersion)))
            )
            properties["OS Version"] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent("Android ${report.raw.device.osVersion} (API ${report.raw.device.sdkInt})")))
            )
            properties["Device Model"] = NotionProperty.RichText(
                listOf(NotionRichText(text = NotionTextContent(report.raw.device.displayName)))
            )
        }
        return properties
    }

    private fun MutableList<NotionBlock>.addStackTrace(report: ProcessedCrashReport) {
        add(NotionBlock(
            type = "code",
            code = NotionCodeBlock(
                rich_text = listOf(NotionRichText(text = NotionTextContent(report.sanitizedStackTrace.take(2000)))),
                language = "kotlin"
            )
        ))
    }

    private fun ProcessedCrashReport.deviceHash(): String {
        val value = "${raw.device.manufacturer}|${raw.device.model}|${raw.device.osVersion}|${raw.device.sdkInt}"
        var hash = 0xcbf29ce484222325uL
        for (char in value) {
            hash = hash xor char.code.toULong()
            hash *= 0x100000001b3uL
        }
        return hash.toString(16).padStart(16, '0')
    }

    private fun ProcessedCrashReport.isRawFallback(): Boolean = confidence <= 0.0f

    private fun ProcessedCrashReport.rawSummary(): String =
        raw.message
            ?.takeIf { it.isNotBlank() }
            ?: sanitizedStackTrace.lineSequence().firstOrNull()
                ?.takeIf { it.isNotBlank() }
            ?: raw.exceptionType

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
