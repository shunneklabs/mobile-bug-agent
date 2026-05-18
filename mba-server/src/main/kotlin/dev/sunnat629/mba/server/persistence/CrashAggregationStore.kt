package dev.sunnat629.mba.server.persistence

import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.model.Severity
import dev.sunnat629.mba.server.model.BugGroup
import dev.sunnat629.mba.server.model.CrashAggregationUpsert
import dev.sunnat629.mba.server.model.CrashOccurrence
import dev.sunnat629.mba.server.model.DeviceSnapshot
import dev.sunnat629.mba.server.model.ExternalSyncState
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CrashAggregationStore(private val dataDir: String = "data") {
    private companion object {
        private const val TAG = "CrashAggregationStore"
        private const val DEFAULT_APP_ID = "mobile-bug-agent-demo"
    }

    private val mutex = Mutex()
    private val groups = linkedMapOf<String, BugGroup>()
    private val occurrences = linkedMapOf<String, CrashOccurrence>()

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val storeFile: File
        get() = File(dataDir, "crash-aggregation-store.json")

    init {
        restore()
    }

    suspend fun upsert(jobId: String, raw: RawCrashReport, report: ProcessedCrashReport): CrashAggregationUpsert {
        return mutex.withLock {
            val appId = raw.appId()
            val environment = raw.environment()
            val groupId = groupId(appId, environment, report.fingerprint)
            val deviceIdHash = raw.device.deviceIdHash()
            val existing = groups[groupId]
            val occurrence = CrashOccurrence(
                id = occurrenceId(jobId, report.fingerprint),
                jobId = jobId,
                bugGroupId = groupId,
                appId = appId,
                environment = environment,
                fingerprint = report.fingerprint,
                deviceIdHash = deviceIdHash,
                deviceDisplayName = raw.device.displayName,
                appVersion = raw.appVersion,
                buildType = raw.buildType,
                screen = raw.currentScreen,
                timestamp = raw.timestamp,
                exceptionType = raw.exceptionType,
                message = report.raw.message,
                sanitizedStackTrace = report.sanitizedStackTrace,
            )

            occurrences.putIfAbsent(occurrence.id, occurrence)
            val occurrenceAdded = occurrences[occurrence.id] === occurrence
            val updated = if (existing == null) {
                report.toNewGroup(groupId, appId, environment, jobId, deviceIdHash)
            } else {
                existing.withOccurrence(report, raw, jobId, deviceIdHash, occurrenceAdded)
            }
            groups[groupId] = updated
            persist()
            CrashAggregationUpsert(
                group = updated,
                occurrence = occurrences[occurrence.id] ?: occurrence,
                isNewGroup = existing == null,
                analysisImproved = existing != null && report.confidence > existing.confidence,
            )
        }
    }

    suspend fun getGroup(id: String): BugGroup? = mutex.withLock { groups[id] }

    suspend fun getGroup(appId: String, environment: String, fingerprint: String): BugGroup? =
        mutex.withLock { groups[groupId(appId, environment, fingerprint)] }

    suspend fun getGroups(): List<BugGroup> =
        mutex.withLock { groups.values.sortedByDescending { it.lastSeen } }

    suspend fun getOccurrences(limit: Int = 100): List<CrashOccurrence> =
        mutex.withLock { occurrences.values.sortedByDescending { it.timestamp }.take(limit) }

    suspend fun markNotionSynced(groupId: String, ticketId: String, url: String?): BugGroup? =
        updateGroup(groupId) {
            it.copy(notionTicketId = ticketId, notionUrl = url, notionSyncState = ExternalSyncState.SYNCED)
        }

    suspend fun markNotionFailed(groupId: String): BugGroup? =
        updateGroup(groupId) { it.copy(notionSyncState = ExternalSyncState.FAILED) }

    suspend fun markGitHubSynced(groupId: String, issueId: String, url: String?): BugGroup? =
        updateGroup(groupId) {
            it.copy(githubIssueId = issueId, githubIssueUrl = url, githubSyncState = ExternalSyncState.SYNCED)
        }

    suspend fun markGitHubFailed(groupId: String): BugGroup? =
        updateGroup(groupId) { it.copy(githubSyncState = ExternalSyncState.FAILED) }

    suspend fun clearAll() {
        mutex.withLock {
            groups.clear()
            occurrences.clear()
            persist()
        }
    }

    private suspend fun updateGroup(groupId: String, update: (BugGroup) -> BugGroup): BugGroup? =
        mutex.withLock {
            val existing = groups[groupId] ?: return@withLock null
            val updated = update(existing)
            groups[groupId] = updated
            persist()
            updated
        }

    private fun ProcessedCrashReport.toNewGroup(
        id: String,
        appId: String,
        environment: String,
        jobId: String,
        deviceIdHash: String,
    ): BugGroup = BugGroup(
        id = id,
        appId = appId,
        environment = environment,
        fingerprint = fingerprint,
        title = title,
        description = description,
        severity = severity,
        confidence = confidence,
        stepsToReproduce = stepsToReproduce,
        possibleCause = possibleCause,
        crashFile = crashFile,
        crashLine = crashLine,
        crashMethod = crashMethod,
        occurrenceCount = 1,
        uniqueDeviceCount = 1,
        deviceMatrix = listOf(raw.device.toSnapshot(deviceIdHash, raw.appVersion, raw.timestamp)),
        firstSeen = raw.timestamp,
        lastSeen = raw.timestamp,
        firstJobId = jobId,
        lastJobId = jobId,
    )

    private fun BugGroup.withOccurrence(
        report: ProcessedCrashReport,
        raw: RawCrashReport,
        jobId: String,
        deviceIdHash: String,
        occurrenceAdded: Boolean,
    ): BugGroup {
        val updatedMatrix = upsertDevice(deviceMatrix, raw.device.toSnapshot(deviceIdHash, raw.appVersion, raw.timestamp))
        val improved = report.confidence > confidence
        return copy(
            title = if (improved) report.title else title,
            description = if (improved) report.description else description,
            severity = if (improved) report.severity else severity,
            confidence = if (improved) report.confidence else confidence,
            stepsToReproduce = if (improved) report.stepsToReproduce ?: stepsToReproduce else stepsToReproduce,
            possibleCause = if (improved) report.possibleCause ?: possibleCause else possibleCause,
            crashFile = if (improved) report.crashFile ?: crashFile else crashFile,
            crashLine = if (improved) report.crashLine ?: crashLine else crashLine,
            crashMethod = if (improved) report.crashMethod ?: crashMethod else crashMethod,
            occurrenceCount = if (occurrenceAdded) occurrenceCount + 1 else occurrenceCount,
            uniqueDeviceCount = updatedMatrix.size,
            deviceMatrix = updatedMatrix,
            lastSeen = maxOf(lastSeen, raw.timestamp),
            lastJobId = jobId,
        )
    }

    private fun upsertDevice(existing: List<DeviceSnapshot>, next: DeviceSnapshot): List<DeviceSnapshot> {
        val current = existing.firstOrNull { it.deviceIdHash == next.deviceIdHash }
        return if (current == null) {
            (existing + next).sortedByDescending { it.lastSeen }
        } else {
            existing.map {
                if (it.deviceIdHash == next.deviceIdHash) {
                    it.copy(
                        appVersion = next.appVersion,
                        occurrences = it.occurrences + 1,
                        lastSeen = maxOf(it.lastSeen, next.lastSeen),
                    )
                } else {
                    it
                }
            }.sortedByDescending { it.lastSeen }
        }
    }

    private fun DeviceContext.toSnapshot(deviceIdHash: String, appVersion: String, timestamp: kotlin.time.Instant): DeviceSnapshot =
        DeviceSnapshot(
            deviceIdHash = deviceIdHash,
            displayName = displayName,
            osVersion = osVersion,
            sdkInt = sdkInt,
            appVersion = appVersion,
            occurrences = 1,
            lastSeen = timestamp,
        )

    private fun RawCrashReport.appId(): String =
        customMetadata["appId"]
            ?: customMetadata["applicationId"]
            ?: customMetadata["packageName"]
            ?: DEFAULT_APP_ID

    private fun RawCrashReport.environment(): String = buildType.ifBlank { "unknown" }

    private fun DeviceContext.deviceIdHash(): String =
        sha256Hex(listOf(manufacturer, model, marketingName.orEmpty(), osVersion, sdkInt.toString(), locale).joinToString("|"))

    private fun groupId(appId: String, environment: String, fingerprint: String): String =
        "bug_${sha256Hex("$appId|$environment|$fingerprint").take(24)}"

    private fun occurrenceId(jobId: String, fingerprint: String): String =
        "occ_${sha256Hex("$jobId|$fingerprint").take(24)}"

    private fun persist() {
        try {
            storeFile.parentFile?.mkdirs()
            storeFile.writeText(json.encodeToString(CrashAggregationStoreData(groups.values.toList(), occurrences.values.toList())))
        } catch (e: Exception) {
            MBALog.e(TAG, "Failed to persist crash aggregation store", e)
        }
    }

    private fun restore() {
        try {
            if (!storeFile.exists()) {
                MBALog.i(TAG, "No crash aggregation store file found — starting fresh")
                return
            }
            val data = json.decodeFromString<CrashAggregationStoreData>(storeFile.readText())
            groups.putAll(data.groups.associateBy { it.id })
            occurrences.putAll(data.occurrences.associateBy { it.id })
            MBALog.i(TAG, "Restored ${groups.size} crash groups and ${occurrences.size} occurrences from disk")
        } catch (e: Exception) {
            MBALog.e(TAG, "Failed to restore crash aggregation store — starting fresh", e)
        }
    }
}

@Serializable
private data class CrashAggregationStoreData(
    val groups: List<BugGroup> = emptyList(),
    val occurrences: List<CrashOccurrence> = emptyList(),
)

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { byte -> (0xFF and byte.toInt()).toString(16).padStart(2, '0') }
}
