package dev.sunnat629.mba.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.sunnat629.mba.agent.AgentFactory
import dev.sunnat629.mba.agent.CrashAnalysisAgent
import dev.sunnat629.mba.agent.runtime.CrashDeliveryPipeline
import dev.sunnat629.mba.agent.runtime.FileLocalCrashAggregationStore
import dev.sunnat629.mba.agent.runtime.LocalFallbackCrashOrchestrator
import dev.sunnat629.mba.agent.runtime.MBAAgentBatchEvent
import dev.sunnat629.mba.agent.runtime.MBAAgentEvent
import dev.sunnat629.mba.agent.runtime.SdkOnlyCrashOrchestrator
import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.config.LLM
import dev.sunnat629.mba.core.config.LLMConfig
import dev.sunnat629.mba.core.pii.PIISanitizer
import dev.sunnat629.mba.core.store.LocalDedupCache
import java.io.File
import kotlin.time.Duration.Companion.hours

/**
 * WorkManager worker that processes pending crash files.
 *
 * Lifecycle:
 * 1. Reads config from SharedPreferences (MBAPreferences)
 * 2. Reads pending crash JSON files from disk (PendingCrashProcessor)
 * 3. For each file:
 *    a. Build ProcessedCrashReport (PII scrub, fingerprint, basic title — no AI)
 *    b. Dedup check (skip if fingerprint already seen)
 *    c. Push raw crash to MBA backend `/report` when configured
 *    d. Emit SDKOnly callback/Flow and optional external sinks
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
        val crashDir = MBAPreferences.loadCrashDir(context)
        val backendEndpoint = MBAPreferences.loadBackendEndpoint(context)
        val projectKey = MBAPreferences.loadProjectKey(context)
        val serverApiKey = MBAPreferences.loadServerApiKey(context)
        val sendToBackend = MBAPreferences.loadSendToBackend(context)
        val llmConfig = loadLlmConfig(context)
        val useAgent = MBAPreferences.loadUseAgent(context)
        val debug = MBAPreferences.loadDebug(context)
        val notionSink = MBAAndroid.notionSink
        val githubSink = MBAAndroid.githubSink
        val fallbackTicketBackend = MBAAndroid.fallbackTicketBackend

        MBALog.enabled = debug

        if (crashDir == null) {
            MBALog.e(TAG, "Missing crashDir config")
            return Result.failure()
        }
        if (!MBAPreferences.isConfigured(context) && fallbackTicketBackend == null) {
            MBALog.e(TAG, "MBA not configured — no backend, LLM, or external ticket backend. Failing.")
            return Result.failure()
        }

        MBALog.d(TAG, "Config loaded: crashDir=$crashDir, backend=${backendEndpoint ?: "none"}, llm=${llmConfig?.provider ?: "none"}, useAgent=$useAgent")

        // 2. Read pending crash files
        val pendingCrashes = PendingCrashProcessor.readPending(crashDir)
        if (pendingCrashes.isEmpty()) {
            MBALog.i(TAG, "No pending crashes to upload. Done.")
            return Result.success()
        }

        MBALog.i(TAG, "Processing ${pendingCrashes.size} pending crash(es)...")

        // 3. Setup dependencies
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
        val aggregationStore = FileLocalCrashAggregationStore(
            File(context.filesDir, "mba-agent/aggregation-store.json")
        )
        val sdkOnlyOrchestrator = llmConfig?.takeIf { useAgent }?.let { config ->
            val factory = AgentFactory(config)
            SdkOnlyCrashOrchestrator(
                analysisAgent = CrashAnalysisAgent(
                    agentFactory = factory,
                    piiSanitizer = PIISanitizer(),
                    dedupCache = dedupCache,
                    useLocalDedup = false,
                ),
                aggregationStore = aggregationStore,
                notionSink = notionSink,
                githubSink = githubSink,
                skipNotion = notionSink == null,
                skipGitIssue = githubSink == null,
            )
        }
        val fallbackOrchestrator = LocalFallbackCrashOrchestrator(
            aggregationStore = aggregationStore,
            notionSink = notionSink,
            githubSink = githubSink,
            skipNotion = notionSink == null,
            skipGitIssue = githubSink == null,
        )
        val deliveryPipeline = CrashDeliveryPipeline(
            rawUploader = serverUploader,
            sdkOnlyOrchestrator = sdkOnlyOrchestrator,
            localFallbackOrchestrator = fallbackOrchestrator,
            fallbackTicketBackend = fallbackTicketBackend,
            fallbackDedupCache = dedupCache,
        )

        var successCount = 0
        var failCount = 0
        val processedEvents = mutableListOf<MBAAgentEvent>()

        // 4. Process each crash file
        for ((file, rawReport) in pendingCrashes) {
            try {
                MBALog.d(TAG, "Processing: ${file.name} (${rawReport.exceptionType})")

                val deliveryResult = deliveryPipeline.process(rawReport)
                val ticketResult = deliveryResult.ticketResult

                if (deliveryResult.success) {
                    MBALog.i(TAG, "Crash processed: ${file.name} via ${deliveryResult.channel} -> ticket=${ticketResult?.ticketId?.take(12) ?: "none"}")
                    deliveryResult.agentEvent?.let { event ->
                        MBALog.i(
                            TAG,
                            "Agent result: source=${event.analysisSource}, agentic=${event.agentic}, " +
                                "confidence=${event.report.confidence}, " +
                                "steps=${event.report.stepsToReproduce?.isNotBlank() == true}, " +
                                "cause=${event.report.possibleCause?.isNotBlank() == true}, " +
                                "notion=${event.externalState.notionTicketId ?: "none"}, " +
                                "github=${event.externalState.githubIssueId ?: "none"}",
                        )
                        event.analysisError?.let { error ->
                            MBALog.w(TAG, "Agent analysis error: $error")
                        }
                        processedEvents += event
                        MBAAndroid.publishAgentEvent(event, notifyAppCallback = false)
                    }
                } else {
                    MBALog.e(TAG, "Crash processing failed for ${file.name}: ${deliveryResult.errorMessage}")
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

        serverUploader?.close()

        publishBatchIfNeeded(processedEvents, successCount, failCount)

        MBALog.i(TAG, "Done: $successCount uploaded, $failCount failed out of ${pendingCrashes.size}")

        return if (failCount > 0 && runAttemptCount < 3) {
            MBALog.w(TAG, "Some uploads failed — requesting retry")
            Result.retry()
        } else {
            Result.success()
        }
    }

    private suspend fun publishBatchIfNeeded(
        events: List<MBAAgentEvent>,
        successCount: Int,
        failCount: Int,
    ) {
        val latest = events.maxByOrNull { it.raw.timestamp } ?: return
        MBAAndroid.publishAgentBatchEvent(
            MBAAgentBatchEvent(
                latest = latest,
                events = events.sortedBy { it.raw.timestamp },
                totalCount = events.size,
                successCount = successCount,
                failCount = failCount,
            )
        )
    }

    private fun loadLlmConfig(context: Context): LLMConfig? {
        val provider = MBAPreferences.loadLlmProvider(context)?.uppercase() ?: return null
        val apiKey = MBAPreferences.loadLlmApiKey(context).orEmpty()
        val model = MBAPreferences.loadLlmModel(context)
        val endpoint = MBAPreferences.loadLlmEndpoint(context)
        val base = when (provider) {
            LLM.Provider.GEMINI.name -> LLM.gemini(apiKey, endpoint = endpoint)
            LLM.Provider.OPENAI.name -> LLM.openAI(apiKey, endpoint = endpoint)
            LLM.Provider.ANTHROPIC.name -> LLM.anthropic(apiKey, endpoint = endpoint)
            LLM.Provider.OLLAMA.name -> LLM.ollama(endpoint = endpoint ?: "http://localhost:11434")
            LLM.Provider.OPENROUTER.name -> LLM.openRouter(apiKey, endpoint = endpoint)
            LLM.Provider.MISTRAL.name -> LLM.mistral(apiKey, endpoint = endpoint)
            LLM.Provider.DEEPSEEK.name -> LLM.deepSeek(apiKey, endpoint = endpoint)
            LLM.Provider.DASHSCOPE.name -> LLM.dashScope(apiKey, endpoint = endpoint)
            LLM.Provider.CUSTOM.name -> {
                if (endpoint.isNullOrBlank() || model.isNullOrBlank()) return null
                LLM.custom(apiKey = apiKey, endpoint = endpoint, model = model)
            }
            else -> return null
        }
        return if (model.isNullOrBlank()) base else base.model(model)
    }
}
