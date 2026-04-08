package dev.sunnat629.mba.notion

import dev.sunnat629.mba.core.model.CrashGroup
import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.store.CrashStore
import dev.sunnat629.mba.notion.model.NotionCreatePageRequest
import dev.sunnat629.mba.notion.model.NotionProperty
import dev.sunnat629.mba.notion.model.NotionQueryDatabaseRequest
import dev.sunnat629.mba.notion.model.NotionUpdatePageRequest
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock

/**
 * Notion implementation of CrashStore.
 *
 * IMPORTANT: This assumes the Notion database has matching property names.
 * You can override them via constructor params.
 */
class NotionCrashStore(
    private val notion: NotionClient,
    private val config: NotionConfig,
    private val propTitle: String = "Title",
    private val propFingerprint: String = "Fingerprint",
    private val propExceptionType: String = "Exception Type",
    private val propSeverity: String = "Severity",
    private val propOccurrenceCount: String = "Occurrence Count",
    private val propStackTrace: String = "Stack Trace",
    private val propCrashFile: String = "Crash File",
    private val propCrashLine: String = "Crash Line",
    private val propAppVersion: String = "App Version",
    private val propAffectedDevices: String = "Affected Devices",
    private val propOsVersions: String = "OS Versions",
    private val propBugTicket: String = "Bug Ticket",
) : CrashStore {

    override suspend fun findByFingerprint(fingerprint: String): CrashGroup? {
        val resp = notion.queryDatabase(
            databaseId = config.databaseId,
            request = NotionQueryDatabaseRequest(
                page_size = 1,
                filter = NotionQueryDatabaseRequest.Filter.RichTextEquals(
                    property = propFingerprint,
                    richText = NotionQueryDatabaseRequest.Filter.RichTextEquals.RichText(equals = fingerprint)
                )
            )
        )

        val page = resp.results.firstOrNull() ?: return null

        // Return minimal CrashGroup to allow incrementCount/linkTicket by id.
        val now = Clock.System.now()
        return CrashGroup(
            id = page.id,
            fingerprint = fingerprint,
            title = "",
            severity = dev.sunnat629.mba.core.model.Severity.MEDIUM,
            firstSeen = now,
            lastSeen = now,
        )
    }

    override suspend fun insertCrash(report: ProcessedCrashReport): CrashGroup {
        val props = linkedMapOf<String, JsonElement>(
            propTitle to NotionProperty.title(report.title),
            propFingerprint to NotionProperty.richText(report.fingerprint),
            propExceptionType to NotionProperty.select(report.raw.exceptionType.substringAfterLast('.')),
            propSeverity to NotionProperty.select(report.severity.name),
            propOccurrenceCount to NotionProperty.number(1),
            propStackTrace to NotionProperty.richText(report.sanitizedStackTrace),
        )

        report.crashFile?.let { props[propCrashFile] = NotionProperty.richText(it) }
        report.crashLine?.let { props[propCrashLine] = NotionProperty.number(it) }
        props[propAppVersion] = NotionProperty.richText(report.raw.appVersion)

        // MVP: affected devices/os versions stored as rich text (to avoid multi-select option management)
        props[propAffectedDevices] = NotionProperty.richText(deviceKey(report.raw.device))
        props[propOsVersions] = NotionProperty.richText(report.raw.device.osVersion)

        val resp = notion.createPage(
            NotionCreatePageRequest(
                parent = NotionCreatePageRequest.Parent(databaseId = config.databaseId),
                properties = props,
            )
        )

        return CrashGroup(
            id = resp.id,
            fingerprint = report.fingerprint,
            title = report.title,
            severity = report.severity,
            occurrenceCount = 1,
            affectedDevices = listOf(deviceKey(report.raw.device)),
            affectedOsVersions = listOf(report.raw.device.osVersion),
            firstSeen = report.raw.timestamp,
            lastSeen = report.raw.timestamp,
            ticketId = null,
        )
    }

    override suspend fun incrementCount(groupId: String, device: DeviceContext) {
        // Read current page to get the current number.
        val page = notion.retrievePage(groupId)
        val currentCount = page.properties[propOccurrenceCount]
            ?.let(NotionProperty::readNumber)
            ?.toInt()
            ?: 1

        val newCount = currentCount + 1

        val props = linkedMapOf<String, JsonElement>()
        props[propOccurrenceCount] = NotionProperty.number(newCount)

        // Best-effort device/os updates (still stored as text for MVP).
        props[propAffectedDevices] = NotionProperty.richText(deviceKey(device))
        props[propOsVersions] = NotionProperty.richText(device.osVersion)

        notion.updatePage(
            pageId = groupId,
            request = NotionUpdatePageRequest(properties = props)
        )
    }

    override suspend fun linkTicket(groupId: String, ticketId: String) {
        val props = linkedMapOf<String, JsonElement>()
        // Proper relation (requires propBugTicket to be a Relation property).
        props[propBugTicket] = NotionProperty.relation(ticketId)

        notion.updatePage(
            pageId = groupId,
            request = NotionUpdatePageRequest(properties = props)
        )
    }

    private fun deviceKey(d: DeviceContext): String = "${d.manufacturer} ${d.model}".trim()
}
