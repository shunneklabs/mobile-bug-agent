package dev.sunnat629.mba.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.sunnat629.mba.agent.AgentFactory
import dev.sunnat629.mba.agent.CrashAnalysisAgent
import dev.sunnat629.mba.agent.runtime.FileLocalCrashAggregationStore
import dev.sunnat629.mba.agent.runtime.MBAAgentEvent
import dev.sunnat629.mba.agent.runtime.MBAAgentSink
import dev.sunnat629.mba.agent.runtime.MBAAgentSinkResult
import dev.sunnat629.mba.agent.runtime.SdkOnlyCrashOrchestrator
import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.config.LLM
import dev.sunnat629.mba.core.config.LLMConfig
import dev.sunnat629.mba.core.model.TicketResult
import dev.sunnat629.mba.core.pii.PIISanitizer
import dev.sunnat629.mba.core.store.LocalDedupCache
import dev.sunnat629.mba.core.ticket.TicketUpdate
import dev.sunnat629.mba.notion.NotionTicketBackend
import java.io.File
import kotlin.time.Duration.Companion.hours

/**
 * WorkManager worker that processes pending crash files and pushes them to Notion/backend.
 *
 * Lifecycle:
 * 1. Reads config from SharedPreferences (MBAPreferences)
 * 2. Reads pending crash JSON files from disk (PendingCrashProcessor)
 * 3. For each file:
 *    a. Build ProcessedCrashReport (PII scrub, fingerprint, basic title — no AI)
 *    b. Dedup check (skip if fingerprint already seen)
 *    c. Push raw crash to MBA backend `/report` when configured
 *    d. Push to Notion (Bug Tickets + Crash Reports DBs)
 *    e. Delete file only when configured destinations succeed
 * 4. Returns Result.success() or Result.retry()
 *
 * Triggered by MBAAndroid.install() on every app startup.
 * Constraints: NetworkType.CONNECTED, exponential backoff.
 */
internal class CrashUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private companion object {
        const val TAG = "UploadWorker"
    }

    override suspend fun doWork(): Result {
        MBALog.i(TAG, "CrashUploadWorker started (attempt ${runAttemptCount + 1})")

        // 1. Load config from SharedPreferences
        val context = applicationContext
        if (!MBAPreferences.isConfigured(context)) {
            MBALog.e(TAG, "MBA not configured — no backend, LLM, or Notion config in SharedPreferences. Failing.")
            return Result.failure()
        }

        val crashDir = MBAPreferences.loadCrashDir(context)
        val notionApiKey = MBAPreferences.loadNotionApiKey(context)
        val ticketDbId = MBAPreferences.loadNotionTicketDbId(context)
        val crashDbId = MBAPreferences.loadNotionCrashDbId(context)
        val backendEndpoint = MBAPreferences.loadBackendEndpoint(context)
        val projectKey = MBAPreferences.loadProjectKey(context)
        val serverApiKey = MBAPreferences.loadServerApiKey(context)
        val sendToBackend = MBAPreferences.loadSendToBackend(context)
        val llmConfig = loadLlmConfig(context)
        val skipGitIssue = MBAPreferences.loadSkipGitIssue(context)
        val debug = MBAPreferences.loadDebug(context)

        MBALog.enabled = debug

        if (crashDir == null) {
            MBALog.e(TAG, "Missing crashDir config")
            return Result.failure()
        }

        MBALog.d(TAG, "Config loaded: crashDir=$crashDir, ticketDb=${ticketDbId?.take(8) ?: "none"}..., crashDb=${crashDbId?.take(8) ?: "none"}")

        // 2. Read pending crash files
        val pendingCrashes = PendingCrashProcessor.readPending(crashDir)
        if (pendingCrashes.isEmpty()) {
            MBALog.i(TAG, "No pending crashes to upload. Done.")
            return Result.success()
        }

        MBALog.i(TAG, "Processing ${pendingCrashes.size} pending crash(es)...")

        // 3. Setup dependencies
        val backend = if (!notionApiKey.isNullOrBlank() && !ticketDbId.isNullOrBlank()) {
            NotionTicketBackend(
                apiKey = notionApiKey,
                bugTicketDbId = ticketDbId,
                crashReportDbId = crashDbId,
            )
        } else {
            null
        }
        val serverUploader = backendEndpoint
            ?.takeIf { sendToBackend }
            ?.let {
                ServerReportUploader(
                    endpoint = it,
                    projectKey = projectKey,
                    serverApiKey = serverApiKey,
                )
            }
        val dedupCache = LocalDedupCache(maxSize = 500, ttl = 24.hours)
        val sdkOnlyOrchestrator = llmConfig?.let { config ->
            val factory = AgentFactory(config)
            SdkOnlyCrashOrchestrator(
                analysisAgent = CrashAnalysisAgent(
                    agentFactory = factory,
                    piiSanitizer = PIISanitizer(),
                    dedupCache = dedupCache,
                    useLocalDedup = false,
                ),
                aggregationStore = FileLocalCrashAggregationStore(
                    File(context.filesDir, "mba-agent/aggregation-store.json")
                ),
                callback = MBAAndroid.agentCallback,
                notionSink = backend?.let(::NotionSdkOnlySink),
                githubSink = null,
                skipNotion = backend == null,
                skipGitIssue = skipGitIssue,
            )
        }

        var successCount = 0
        var failCount = 0

        // 4. Process each crash file
        for ((file, rawReport) in pendingCrashes) {
            try {
                MBALog.d(TAG, "Processing: ${file.name} (${rawReport.exceptionType})")

                val serverResult = if (serverUploader != null) {
                    MBALog.d(TAG, "Uploading raw crash to backend: $backendEndpoint/report")
                    when (val backendResult = serverUploader.upload(rawReport)) {
                        is BackendUploadResult.Accepted -> {
                            MBALog.i(TAG, "✅ Backend accepted: ${file.name} → job=${backendResult.jobId.take(12)}... (${backendResult.status})")
                            BackendDelivery.Accepted
                        }
                        is BackendUploadResult.Rejected -> {
                            MBALog.e(TAG, "❌ Backend rejected ${file.name}: HTTP ${backendResult.statusCode} ${backendResult.reason}")
                            BackendDelivery.Rejected
                        }
                    }
                } else {
                    BackendDelivery.NotConfigured
                }

                var fingerprintToCache: String? = null
                val deliveryResult = if (serverResult == BackendDelivery.Accepted) {
                    MBALog.i(TAG, "Backend accepted crash; skipping direct Notion upload to avoid duplicate tickets")
                    TicketResult(
                        ticketId = "backend",
                        backendName = "Backend",
                        success = true,
                    )
                } else if (sdkOnlyOrchestrator != null) {
                    MBALog.d(TAG, "Running SDKOnly Koog agent for '${rawReport.exceptionType}'")
                    sdkOnlyOrchestrator.process(rawReport)
                    TicketResult(ticketId = "sdk-only", backendName = "SDKOnly", success = true)
                } else {
                    // Build ProcessedCrashReport (no AI)
                    val report = CrashReportBuilder.build(rawReport)
                    fingerprintToCache = report.fingerprint

                    // Dedup check for legacy direct Notion fallback only.
                    if (dedupCache.contains(report.fingerprint)) {
                        MBALog.w(TAG, "Duplicate: ${report.fingerprint.take(12)}... — deleting file")
                        file.delete()
                        successCount++
                        continue
                    }

                    // Direct Notion is a fallback/local mode only. The server owns grouping when configured.
                    if (backend == null) {
                        TicketResult.failure("SDKOnly", "No LLM callback or Notion backend configured for local processing")
                    } else {
                        MBALog.d(TAG, "Uploading to Notion: '${report.title}'")
                        backend.createTicket(report)
                    }
                }

                if (deliveryResult.success) {
                    MBALog.i(TAG, "✅ Crash processed: ${file.name} → ticket=${deliveryResult.ticketId.take(12)}...")
                    fingerprintToCache?.let { dedupCache.put(it) }
                } else {
                    MBALog.e(TAG, "❌ Crash processing failed for ${file.name}: ${deliveryResult.errorMessage}")
                }

                if (deliveryResult.success) {
                    file.delete()
                    successCount++
                } else {
                    failCount++
                }
            } catch (e: Exception) {
                MBALog.e(TAG, "❌ Error processing ${file.name}", e)
                failCount++
            }
        }

        backend?.close()
        serverUploader?.close()

        MBALog.i(TAG, "Done: $successCount uploaded, $failCount failed out of ${pendingCrashes.size}")

        return if (failCount > 0 && runAttemptCount < 3) {
            MBALog.w(TAG, "Some uploads failed — requesting retry")
            Result.retry()
        } else {
            Result.success()
        }
    }

    private enum class BackendDelivery {
        Accepted,
        Rejected,
        NotConfigured,
    }

    private fun loadLlmConfig(context: Context): LLMConfig? {
        val apiKey = MBAPreferences.loadLlmApiKey(context) ?: return null
        val provider = MBAPreferences.loadLlmProvider(context)?.uppercase()
        val model = MBAPreferences.loadLlmModel(context)
        val base = when (provider) {
            LLM.Provider.OPENAI.name -> LLM.openAI(apiKey)
            else -> LLM.gemini(apiKey)
        }
        return if (model.isNullOrBlank()) base else base.model(model)
    }

    private class NotionSdkOnlySink(
        private val backend: NotionTicketBackend,
    ) : MBAAgentSink {
        override val name: String = "Notion"

        override suspend fun sync(event: MBAAgentEvent): MBAAgentSinkResult {
            val existingTicketId = event.group.notionTicketId
            return if (!event.isNewGroup && existingTicketId != null) {
                val update = backend.updateTicket(
                    existingTicketId,
                    TicketUpdate(
                        incrementCount = true,
                        occurrenceCount = event.group.occurrenceCount,
                        uniqueDeviceCount = event.group.uniqueDeviceCount,
                        newOccurrenceTime = event.occurrence.occurredAt,
                    ),
                )
                backend.createCrashOccurrence(event.report, existingTicketId)
                update.toSinkResult(created = false)
            } else {
                backend.createTicket(event.report).toSinkResult(created = true)
            }
        }

        private fun TicketResult.toSinkResult(created: Boolean): MBAAgentSinkResult =
            MBAAgentSinkResult(
                ticketId = ticketId.takeIf { it.isNotBlank() },
                url = url,
                created = created,
                success = success,
                errorMessage = errorMessage,
            )
    }
}
