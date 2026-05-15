package dev.sunnat629.mba.server.queue

import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.server.model.JobStatus
import dev.sunnat629.mba.server.persistence.JobStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.slf4j.LoggerFactory

/**
 * SSE event emitted when a job transitions state.
 */
data class SseEvent(
    val jobId: String,
    val status: JobStatus,
    val timestamp: Long,
    val artifactUrl: String?,
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
        private val logger = LoggerFactory.getLogger("CrashProcessingQueue")
    }

    private val eventChannel = Channel<SseEvent>(channelCapacity)
    private val jobChannel = Channel<CrashJob>(Channel.UNLIMITED)

    /** Flow of SSE events for the /events endpoint. */
    val events: Flow<SseEvent> = eventChannel.receiveAsFlow()

    /** Channel for submitting crash jobs to the background consumer. */
    val jobs: Channel<CrashJob> = jobChannel

    /** Enqueue a crash job. Returns the job id. */
    suspend fun enqueue(jobId: String, report: RawCrashReport): String {
        jobStore.createJob(jobId)
        emitEvent(jobId, JobStatus.QUEUED)
        jobChannel.send(CrashJob(jobId, report))
        logger.info("Enqueued job $jobId")
        return jobId
    }

    /** Mark a job as started processing. */
    suspend fun startProcessing(jobId: String) {
        jobStore.updateStatus(jobId, JobStatus.ANALYZING)
        emitEvent(jobId, JobStatus.ANALYZING)
    }

    /** Mark a job as completed with an artifact URL. */
    suspend fun complete(jobId: String, artifactUrl: String) {
        jobStore.updateStatus(jobId, JobStatus.TICKET_CREATED, artifactUrl = artifactUrl)
        emitEvent(jobId, JobStatus.TICKET_CREATED, artifactUrl)
    }

    /** Mark a job as PR opened. */
    suspend fun prOpened(jobId: String, prUrl: String) {
        jobStore.updateStatus(jobId, JobStatus.PR_OPENED, artifactUrl = prUrl)
        emitEvent(jobId, JobStatus.PR_OPENED, prUrl)
    }

    /** Mark a job as failed. */
    suspend fun fail(jobId: String, errorMessage: String) {
        jobStore.updateStatus(jobId, JobStatus.FAILED, errorMessage = errorMessage)
        emitEvent(jobId, JobStatus.FAILED)
        logger.error("Job $jobId failed: $errorMessage")
    }

    private fun emitEvent(jobId: String, status: JobStatus, artifactUrl: String? = null) {
        eventChannel.trySend(
            SseEvent(
                jobId = jobId,
                status = status,
                timestamp = System.currentTimeMillis(),
                artifactUrl = artifactUrl,
            )
        )
    }
}
