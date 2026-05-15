package dev.sunnat629.mba.server.persistence

import dev.sunnat629.mba.server.model.JobState
import dev.sunnat629.mba.server.model.JobStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * In-memory job store with file-based persistence.
 * Survives server restarts for booth demo reliability.
 */
class JobStore(private val dataDir: String = "data") {

    private companion object {
        private const val TAG = "JobStore"
        private val logger = LoggerFactory.getLogger(TAG)
    }

    private val mutex = Mutex()
    private val jobs = mutableMapOf<String, JobState>()

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val storeFile: File
        get() = File(dataDir, "job-store.json")

    init {
        restore()
    }

    suspend fun createJob(id: String): JobState {
        return mutex.withLock {
            val now = System.currentTimeMillis()
            val job = JobState(
                id = id,
                status = JobStatus.QUEUED,
                createdAt = now,
                updatedAt = now,
            )
            jobs[id] = job
            persist()
            job
        }
    }

    suspend fun updateStatus(id: String, status: JobStatus, artifactUrl: String? = null, errorMessage: String? = null): JobState? {
        return mutex.withLock {
            val existing = jobs[id] ?: return@withLock null
            val updated = existing.copy(
                status = status,
                updatedAt = System.currentTimeMillis(),
                artifactUrl = artifactUrl ?: existing.artifactUrl,
                errorMessage = errorMessage ?: existing.errorMessage,
            )
            jobs[id] = updated
            persist()
            updated
        }
    }

    suspend fun getJob(id: String): JobState? {
        return mutex.withLock { jobs[id] }
    }

    suspend fun getAllJobs(): List<JobState> {
        return mutex.withLock { jobs.values.toList().sortedByDescending { it.createdAt } }
    }

    suspend fun getStats(): JobStoreStats {
        return mutex.withLock {
            JobStoreStats(
                total = jobs.size,
                queued = jobs.count { it.value.status == JobStatus.QUEUED },
                completed = jobs.count { it.value.status == JobStatus.TICKET_CREATED || it.value.status == JobStatus.PR_OPENED },
                failed = jobs.count { it.value.status == JobStatus.FAILED },
            )
        }
    }

    suspend fun clearAll() {
        mutex.withLock {
            jobs.clear()
            persist()
        }
    }

    private fun persist() {
        try {
            val data = JobStoreData(
                jobs = jobs.mapValues { (_, job) -> PersistedJobState(job.id, job.status.name, job.createdAt, job.updatedAt, job.artifactUrl, job.errorMessage) }
            )
            storeFile.parentFile?.mkdirs()
            storeFile.writeText(json.encodeToString(data))
        } catch (e: Exception) {
            logger.error("Failed to persist job store", e)
        }
    }

    private fun restore() {
        try {
            if (!storeFile.exists()) {
                logger.info("No job store file found — starting fresh")
                return
            }
            val data = json.decodeFromString<JobStoreData>(storeFile.readText())
            jobs.putAll(data.jobs.mapValues { (_, p) ->
                JobState(p.id, JobStatus.valueOf(p.status), p.createdAt, p.updatedAt, p.artifactUrl, p.errorMessage)
            })
            logger.info("Restored ${jobs.size} jobs from disk")
        } catch (e: Exception) {
            logger.error("Failed to restore job store — starting fresh", e)
        }
    }
}

data class JobStoreStats(
    val total: Int,
    val queued: Int,
    val completed: Int,
    val failed: Int,
)

@Serializable
private data class JobStoreData(
    val jobs: Map<String, PersistedJobState> = emptyMap(),
)

@Serializable
private data class PersistedJobState(
    val id: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val artifactUrl: String?,
    val errorMessage: String?,
)
