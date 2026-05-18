package dev.sunnat629.mba.server.model

import dev.sunnat629.mba.core.model.Severity
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class CrashOccurrence(
    val id: String,
    val jobId: String,
    val bugGroupId: String,
    val appId: String,
    val environment: String,
    val fingerprint: String,
    val deviceIdHash: String,
    val deviceDisplayName: String,
    val appVersion: String,
    val buildType: String,
    val screen: String? = null,
    val timestamp: Instant,
    val exceptionType: String,
    val message: String? = null,
    val sanitizedStackTrace: String,
)

@Serializable
data class BugGroup(
    val id: String,
    val appId: String,
    val environment: String,
    val fingerprint: String,
    val title: String,
    val description: String,
    val severity: Severity,
    val confidence: Float,
    val stepsToReproduce: String? = null,
    val possibleCause: String? = null,
    val crashFile: String? = null,
    val crashLine: Int? = null,
    val crashMethod: String? = null,
    val occurrenceCount: Int,
    val uniqueDeviceCount: Int,
    val deviceMatrix: List<DeviceSnapshot>,
    val firstSeen: Instant,
    val lastSeen: Instant,
    val firstJobId: String,
    val lastJobId: String,
    val notionTicketId: String? = null,
    val notionUrl: String? = null,
    val notionSyncState: ExternalSyncState = ExternalSyncState.NOT_REQUESTED,
    val githubIssueId: String? = null,
    val githubIssueUrl: String? = null,
    val githubSyncState: ExternalSyncState = ExternalSyncState.NOT_REQUESTED,
)

@Serializable
data class DeviceSnapshot(
    val deviceIdHash: String,
    val displayName: String,
    val osVersion: String,
    val sdkInt: Int,
    val appVersion: String,
    val occurrences: Int,
    val lastSeen: Instant,
)

@Serializable
enum class ExternalSyncState {
    NOT_REQUESTED,
    IN_PROGRESS,
    SYNCED,
    FAILED,
}

data class CrashAggregationUpsert(
    val group: BugGroup,
    val occurrence: CrashOccurrence,
    val isNewGroup: Boolean,
    val analysisImproved: Boolean,
)
