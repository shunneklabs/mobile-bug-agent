package dev.sunnat629.mba.agent.runtime

import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.RawCrashReport
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

public class FileLocalCrashAggregationStore(
    private val file: File,
) : LocalCrashAggregationStore {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private var data: LocalStoreData = restore()

    override suspend fun upsert(raw: RawCrashReport, report: ProcessedCrashReport): LocalAggregationResult =
        mutex.withLock {
            val appId = raw.customMetadata["appId"] ?: raw.customMetadata["applicationId"] ?: "unknown-app"
            val environment = raw.buildType.ifBlank { "unknown" }
            val groupId = "bug_${sha256Hex("$appId|$environment|${report.fingerprint}").take(24)}"
            val occurrenceId = "occ_${sha256Hex("${raw.id}|${report.fingerprint}").take(24)}"
            val deviceHash = raw.device.deviceHash()
            val existing = data.groups[groupId]
            val existingOccurrence = data.occurrences[occurrenceId]
            val occurrence = existingOccurrence ?: LocalCrashOccurrence(
                id = occurrenceId,
                bugGroupId = groupId,
                fingerprint = report.fingerprint,
                deviceIdHash = deviceHash,
                occurredAt = raw.timestamp,
                appVersion = raw.appVersion,
                osVersion = "Android ${raw.device.osVersion} (API ${raw.device.sdkInt})",
                deviceModel = raw.device.displayName,
                screen = raw.currentScreen,
            )
            val deviceSet = (existing?.deviceHashes ?: emptySet()) + deviceHash
            val group = if (existing == null) {
                StoredBugGroup(
                    group = LocalBugGroup(
                        id = groupId,
                        appId = appId,
                        environment = environment,
                        fingerprint = report.fingerprint,
                        occurrenceCount = 1,
                        uniqueDeviceCount = 1,
                        firstSeen = raw.timestamp,
                        lastSeen = raw.timestamp,
                    ),
                    deviceHashes = deviceSet,
                )
            } else {
                existing.copy(
                    group = existing.group.copy(
                        occurrenceCount = if (existingOccurrence == null) existing.group.occurrenceCount + 1 else existing.group.occurrenceCount,
                        uniqueDeviceCount = deviceSet.size,
                        lastSeen = maxOf(existing.group.lastSeen, raw.timestamp),
                    ),
                    deviceHashes = deviceSet,
                )
            }
            data = data.copy(
                groups = data.groups + (groupId to group),
                occurrences = data.occurrences + (occurrenceId to occurrence),
            )
            persist()
            LocalAggregationResult(group.group, occurrence, existing == null)
        }

    override suspend fun markNotionSynced(groupId: String, ticketId: String, url: String?) {
        updateGroup(groupId) {
            it.copy(group = it.group.copy(notionTicketId = ticketId, notionUrl = url))
        }
    }

    override suspend fun markGitHubSynced(groupId: String, issueId: String, url: String?) {
        updateGroup(groupId) {
            it.copy(group = it.group.copy(githubIssueId = issueId, githubIssueUrl = url))
        }
    }

    private suspend fun updateGroup(groupId: String, update: (StoredBugGroup) -> StoredBugGroup) {
        mutex.withLock {
            val existing = data.groups[groupId] ?: return@withLock
            data = data.copy(groups = data.groups + (groupId to update(existing)))
            persist()
        }
    }

    private fun restore(): LocalStoreData =
        runCatching {
            if (!file.exists()) LocalStoreData()
            else json.decodeFromString<LocalStoreData>(file.readText())
        }.getOrElse { LocalStoreData() }

    private fun persist() {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(data))
    }

    private fun DeviceContext.deviceHash(): String =
        sha256Hex(listOf(manufacturer, model, marketingName.orEmpty(), osVersion, sdkInt.toString(), locale).joinToString("|"))
}

@Serializable
private data class LocalStoreData(
    val groups: Map<String, StoredBugGroup> = emptyMap(),
    val occurrences: Map<String, LocalCrashOccurrence> = emptyMap(),
)

@Serializable
private data class StoredBugGroup(
    val group: LocalBugGroup,
    val deviceHashes: Set<String> = emptySet(),
)

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { byte -> (0xFF and byte.toInt()).toString(16).padStart(2, '0') }
}
