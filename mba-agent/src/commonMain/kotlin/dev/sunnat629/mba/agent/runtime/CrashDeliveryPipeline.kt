package dev.sunnat629.mba.agent.runtime

import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.model.TicketResult
import dev.sunnat629.mba.core.processing.CrashReportBuilder
import dev.sunnat629.mba.core.store.LocalDedupCache
import dev.sunnat629.mba.core.ticket.TicketBackend
import kotlinx.coroutines.CancellationException

/**
 * Platform-neutral crash delivery flow used by SDK adapters.
 *
 * Adapters provide platform capture, storage, scheduling, and concrete sinks.
 * This pipeline owns the shared decision order:
 * backend upload, SDKOnly agent, then legacy local ticket fallback.
 */
public class CrashDeliveryPipeline(
    private val rawUploader: RawCrashUploader? = null,
    private val sdkOnlyOrchestrator: SdkOnlyCrashOrchestrator? = null,
    private val fallbackTicketBackend: TicketBackend? = null,
    private val fallbackDedupCache: LocalDedupCache = LocalDedupCache(),
) {
    public suspend fun process(rawReport: RawCrashReport): CrashDeliveryResult {
        rawUploader?.let { uploader ->
            MBALog.d(TAG, "Uploading raw crash to backend")
            try {
                when (val result = uploader.upload(rawReport)) {
                    is RawCrashUploadResult.Accepted -> {
                        MBALog.i(TAG, "Backend accepted crash: job=${result.jobId.take(12)} (${result.status})")
                        return CrashDeliveryResult.backendAccepted(result.jobId, result.status)
                    }
                    is RawCrashUploadResult.Rejected -> {
                        MBALog.e(TAG, "Backend rejected crash: HTTP ${result.statusCode} ${result.reason}")
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                MBALog.e(TAG, "Backend upload failed; falling back to SDKOnly/local processing", error)
            }
        }

        sdkOnlyOrchestrator?.let { orchestrator ->
            MBALog.d(TAG, "Running SDKOnly Koog agent for '${rawReport.exceptionType}'")
            val event = orchestrator.process(rawReport)
            return CrashDeliveryResult.sdkOnly(event)
        }

        val fallbackReport = CrashReportBuilder.build(rawReport)
        if (fallbackDedupCache.contains(fallbackReport.fingerprint)) {
            MBALog.w(TAG, "Duplicate fallback crash: ${fallbackReport.fingerprint.take(12)}")
            return CrashDeliveryResult.duplicate(fallbackReport.fingerprint)
        }

        val backend = fallbackTicketBackend
            ?: return CrashDeliveryResult.failure("No SDKOnly agent or fallback ticket backend configured")

        MBALog.d(TAG, "Creating fallback ticket: '${fallbackReport.title}'")
        val ticketResult = backend.createTicket(fallbackReport)
        if (ticketResult.success) {
            fallbackDedupCache.put(fallbackReport.fingerprint)
        }
        return CrashDeliveryResult.fallbackTicket(ticketResult)
    }

    private companion object {
        const val TAG = "CrashPipeline"
    }
}

public interface RawCrashUploader {
    public suspend fun upload(rawReport: RawCrashReport): RawCrashUploadResult
}

public sealed interface RawCrashUploadResult {
    public data class Accepted(
        val jobId: String,
        val status: String,
    ) : RawCrashUploadResult

    public data class Rejected(
        val statusCode: Int,
        val reason: String,
    ) : RawCrashUploadResult
}

public data class CrashDeliveryResult(
    val channel: CrashDeliveryChannel,
    val success: Boolean,
    val ticketResult: TicketResult? = null,
    val agentEvent: MBAAgentEvent? = null,
    val backendJobId: String? = null,
    val backendStatus: String? = null,
    val duplicateFingerprint: String? = null,
    val errorMessage: String? = null,
) {
    public companion object {
        public fun backendAccepted(jobId: String, status: String): CrashDeliveryResult =
            CrashDeliveryResult(
                channel = CrashDeliveryChannel.BACKEND,
                success = true,
                backendJobId = jobId,
                backendStatus = status,
                ticketResult = TicketResult(
                    ticketId = "backend",
                    backendName = "Backend",
                    success = true,
                ),
            )

        public fun sdkOnly(event: MBAAgentEvent): CrashDeliveryResult =
            CrashDeliveryResult(
                channel = CrashDeliveryChannel.SDK_ONLY,
                success = true,
                agentEvent = event,
                ticketResult = TicketResult(
                    ticketId = "sdk-only",
                    backendName = "SDKOnly",
                    success = true,
                ),
            )

        public fun duplicate(fingerprint: String): CrashDeliveryResult =
            CrashDeliveryResult(
                channel = CrashDeliveryChannel.DUPLICATE,
                success = true,
                duplicateFingerprint = fingerprint,
                ticketResult = TicketResult(
                    ticketId = "duplicate",
                    backendName = "LocalDedup",
                    success = true,
                ),
            )

        public fun fallbackTicket(ticketResult: TicketResult): CrashDeliveryResult =
            CrashDeliveryResult(
                channel = CrashDeliveryChannel.FALLBACK_TICKET,
                success = ticketResult.success,
                ticketResult = ticketResult,
                errorMessage = ticketResult.errorMessage,
            )

        public fun failure(message: String): CrashDeliveryResult =
            CrashDeliveryResult(
                channel = CrashDeliveryChannel.NONE,
                success = false,
                errorMessage = message,
                ticketResult = TicketResult.failure("SDKOnly", message),
            )
    }
}

public enum class CrashDeliveryChannel {
    BACKEND,
    SDK_ONLY,
    FALLBACK_TICKET,
    DUPLICATE,
    NONE,
}
