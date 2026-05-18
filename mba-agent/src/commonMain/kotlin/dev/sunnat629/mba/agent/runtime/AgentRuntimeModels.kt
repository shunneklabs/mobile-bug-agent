package dev.sunnat629.mba.agent.runtime

import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.RawCrashReport
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
public data class LocalBugGroup(
    val id: String,
    val appId: String,
    val environment: String,
    val fingerprint: String,
    val occurrenceCount: Int,
    val uniqueDeviceCount: Int,
    val firstSeen: Instant,
    val lastSeen: Instant,
    val notionTicketId: String? = null,
    val notionUrl: String? = null,
    val githubIssueId: String? = null,
    val githubIssueUrl: String? = null,
)

@Serializable
public data class LocalCrashOccurrence(
    val id: String,
    val bugGroupId: String,
    val fingerprint: String,
    val deviceIdHash: String,
    val occurredAt: Instant,
    val appVersion: String,
    val osVersion: String,
    val deviceModel: String,
    val screen: String? = null,
)

@Serializable
public data class ExternalArtifactState(
    val notionTicketId: String? = null,
    val notionUrl: String? = null,
    val githubIssueId: String? = null,
    val githubIssueUrl: String? = null,
)

@Serializable
public data class MBAAgentEvent(
    val mode: String,
    val group: LocalBugGroup,
    val occurrence: LocalCrashOccurrence,
    val report: ProcessedCrashReport,
    val raw: RawCrashReport,
    val externalState: ExternalArtifactState,
    val isNewGroup: Boolean,
    val agentic: Boolean = true,
    val analysisSource: String = "KOOG",
    val analysisError: String? = null,
)

@Serializable
public data class MBAAgentBatchEvent(
    val latest: MBAAgentEvent,
    val events: List<MBAAgentEvent>,
    val totalCount: Int,
    val successCount: Int,
    val failCount: Int,
)

public fun interface MBAAgentCallback {
    public suspend fun onCrashAnalyzed(event: MBAAgentEvent)
}

public fun interface MBAAgentBatchCallback {
    public suspend fun onCrashesAnalyzed(batch: MBAAgentBatchEvent)
}

public interface LocalCrashAggregationStore {
    public suspend fun upsert(raw: RawCrashReport, report: ProcessedCrashReport): LocalAggregationResult
    public suspend fun markNotionSynced(groupId: String, ticketId: String, url: String?)
    public suspend fun markGitHubSynced(groupId: String, issueId: String, url: String?)
}

public data class LocalAggregationResult(
    val group: LocalBugGroup,
    val occurrence: LocalCrashOccurrence,
    val isNewGroup: Boolean,
)

public interface MBAAgentSink {
    public val name: String
    public suspend fun sync(event: MBAAgentEvent): MBAAgentSinkResult
}

public data class MBAAgentSinkResult(
    val ticketId: String? = null,
    val url: String? = null,
    val created: Boolean = false,
    val success: Boolean = true,
    val errorMessage: String? = null,
)
