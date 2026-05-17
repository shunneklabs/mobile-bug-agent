package dev.sunnat629.mba.server.queue

import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.server.model.JobStatus
import dev.sunnat629.mba.server.persistence.JobStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable

/**
 * SSE event emitted when a job transitions state.
 */
@Serializable
data class SseEvent(
    val jobId: String,
    val status: JobStatus,
    val type: String = "job",
    val stage: String,
    val step: String = stage,
    val message: String,
    val level: String = "info",
    val timestamp: Long,
    val artifactType: String? = null,
    val artifactUrl: String?,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Payload for a crash processing job.
 */
data class CrashJob(
    val jobId: String,
    val report: RawCrashReport,
)

/**
 * In-memory crash processing queue with at-least-once semantics.
 * Emits state transitions as SSE events for the booth TV.
 */
class CrashProcessingQueue(
    private val jobStore: JobStore,
    private val channelCapacity: Int = Channel.UNLIMITED,
) {

    private companion object {
        private const val TAG = "CrashProcessingQueue"
    }

    private val eventFlow = MutableSharedFlow<SseEvent>(
        replay = 64,
        extraBufferCapacity = channelCapacity,
    )
    private val jobChannel = Channel<CrashJob>(Channel.UNLIMITED)

    /** Broadcast flow of SSE events for the /events endpoint. */
    val events: Flow<SseEvent> = eventFlow.asSharedFlow()

    /** Channel for submitting crash jobs to the background consumer. */
    val jobs: Channel<CrashJob> = jobChannel

    /** Enqueue a crash job. Returns the job id. */
    suspend fun enqueue(jobId: String, report: RawCrashReport): String {
        jobStore.createJob(jobId, report)
        emitEvent(jobId, JobStatus.QUEUED, message = "Crash report queued")
        jobChannel.send(CrashJob(jobId, report))
        MBALog.i(TAG, "Enqueued job $jobId")
        return jobId
    }

    /** Mark a job as started processing. */
    suspend fun startProcessing(jobId: String) {
        jobStore.updateStatus(jobId, JobStatus.ANALYZING)
        emitEvent(jobId, JobStatus.ANALYZING, message = "Koog agent analyzing stacktrace")
    }

    /** Mark a job as completed with an artifact URL. */
    suspend fun complete(jobId: String, artifactUrl: String, artifactType: String = artifactTypeForUrl(artifactUrl)) {
        jobStore.updateStatus(jobId, JobStatus.TICKET_CREATED, artifactUrl = artifactUrl)
        emitEvent(jobId, JobStatus.TICKET_CREATED, artifactUrl, artifactType, message = completionMessageFor(artifactType))
    }

    /** Mark a job as PR opened. */
    suspend fun prOpened(jobId: String, prUrl: String, artifactType: String = "pull_request") {
        jobStore.updateStatus(jobId, JobStatus.PR_OPENED, artifactUrl = prUrl)
        emitEvent(jobId, JobStatus.PR_OPENED, prUrl, artifactType, message = completionMessageFor(artifactType))
    }

    /**
     * Emit a fine-grained progress event inside one of the lifecycle stages
     * (`analyzing`, `notion_ticket`, `github_pr`).
     *
     * Does NOT change the job's persisted [JobStatus] — those transitions
     * still happen via [startProcessing] / [complete] / [prOpened] / [fail].
     * This is purely a booth-visibility helper for the long invisible gaps
     * (PII / fingerprint / dedup / Gemini call / Notion sub-calls / GitHub steps).
     */
    suspend fun progress(
        jobId: String,
        message: String,
        stage: String = "analyzing",
        level: String = "info",
        metadata: Map<String, String> = emptyMap(),
    ) {
        eventFlow.emit(
            SseEvent(
                jobId = jobId,
                status = JobStatus.ANALYZING,
                type = "progress",
                stage = stage,
                message = message,
                level = level,
                timestamp = System.currentTimeMillis(),
                artifactUrl = null,
                metadata = metadata,
            )
        )
        MBALog.i(TAG, "Job $jobId [$stage] $message")
    }

    /** Mark a job as failed. */
    suspend fun fail(jobId: String, errorMessage: String) {
        jobStore.updateStatus(jobId, JobStatus.FAILED, errorMessage = errorMessage)
        emitEvent(jobId, JobStatus.FAILED, message = "Job failed: $errorMessage", level = "error")
        MBALog.e(TAG, "Job $jobId failed: $errorMessage")
    }

    suspend fun publishBoothEvent(
        type: String,
        message: String,
        level: String = "info",
        metadata: Map<String, String> = emptyMap(),
        jobId: String = "booth",
    ) {
        eventFlow.emit(
            SseEvent(
                jobId = jobId,
                status = JobStatus.QUEUED,
                type = type,
                stage = type,
                message = message,
                level = level,
                timestamp = System.currentTimeMillis(),
                artifactUrl = null,
                metadata = metadata,
            )
        )
    }

    private fun emitEvent(
        jobId: String,
        status: JobStatus,
        artifactUrl: String? = null,
        artifactType: String? = artifactUrl?.let(::artifactTypeForUrl),
        message: String,
        level: String = "info",
    ) {
        val stage = when (status) {
            JobStatus.QUEUED -> "queued"
            JobStatus.ANALYZING -> "analyzing"
            JobStatus.TICKET_CREATED -> "notion_ticket"
            JobStatus.PR_OPENED -> "github_pr"
            JobStatus.FAILED -> "failed"
        }
        eventFlow.tryEmit(
            SseEvent(
                jobId = jobId,
                status = status,
                stage = stage,
                message = message,
                level = level,
                timestamp = System.currentTimeMillis(),
                artifactType = artifactType,
                artifactUrl = artifactUrl,
            )
        )
    }
}

internal fun artifactTypeForUrl(url: String): String = when {
    url.startsWith("https://github.com/") && "/pull/" in url -> "pull_request"
    url.startsWith("https://github.com/") && "/issues/" in url -> "github_issue"
    url.startsWith("https://www.notion.so/") || url.startsWith("https://notion.so/") -> "notion_ticket"
    url.startsWith("notion://") -> "notion_ticket"
    url.startsWith("duplicate://") -> "duplicate"
    url.startsWith("analysis://") -> "analysis"
    else -> "artifact"
}

private fun completionMessageFor(artifactType: String): String = when (artifactType) {
    "pull_request" -> "Pull request opened"
    "github_issue" -> "GitHub issue created"
    "notion_ticket" -> "Notion ticket created"
    "duplicate" -> "Duplicate crash recorded"
    "analysis" -> "Analysis result recorded"
    else -> "Artifact created"
}
