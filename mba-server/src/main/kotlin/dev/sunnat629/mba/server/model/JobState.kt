package dev.sunnat629.mba.server.model

import dev.sunnat629.mba.core.model.RawCrashReport
import kotlinx.serialization.Serializable

@Serializable
enum class JobStatus {
    QUEUED,
    ANALYZING,
    TICKET_CREATED,
    PR_OPENED,
    FAILED,
}

@Serializable
data class JobState(
    val id: String,
    val status: JobStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val artifactUrl: String? = null,
    val errorMessage: String? = null,
    val rawReport: RawCrashReport? = null,
)

@Serializable
data class ServerVersion(
    val version: String = "0.1.0-kotlinconf",
    val koogVersion: String = "0.8.0",
    val buildTime: String = System.getenv("MBA_BUILD_TIME") ?: "local",
)

@Serializable
data class ServerStats(
    val totalJobs: Int,
    val queuedJobs: Int,
    val completedJobs: Int,
    val failedJobs: Int,
    val dedupCacheSize: Int,
)
