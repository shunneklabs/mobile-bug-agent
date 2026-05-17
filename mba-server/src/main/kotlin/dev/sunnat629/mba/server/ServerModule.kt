package dev.sunnat629.mba.server

import dev.sunnat629.mba.agent.AgentFactory
import dev.sunnat629.mba.agent.CrashAnalysisAgent
import dev.sunnat629.mba.core.config.LLM
import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.config.LLMConfig
import dev.sunnat629.mba.core.pii.PIISanitizer
import dev.sunnat629.mba.core.store.LocalDedupCache
import dev.sunnat629.mba.github.GitHubAutoFixOpener
import dev.sunnat629.mba.notion.NotionTicketBackend
import dev.sunnat629.mba.server.orchestration.CrashAnalysisTool
import dev.sunnat629.mba.server.orchestration.DemoEventSink
import dev.sunnat629.mba.server.orchestration.DemoOrchestrator
import dev.sunnat629.mba.server.orchestration.GitHubAutoFixTool
import dev.sunnat629.mba.server.orchestration.SeverityRouter
import dev.sunnat629.mba.server.persistence.JobStore
import dev.sunnat629.mba.server.queue.CrashProcessingQueue
import io.ktor.server.application.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.hours

/**
 * Dependency graph for the MBA server.
 * All shared state and services live here.
 */
class ServerModule(
    val geminiApiKey: String,
    val notionApiKey: String,
    val notionDatabaseId: String,
    val serverApiKey: String,
    val dedupCachePath: String,
    val dataDir: String = "data",
    val githubToken: String = "",
    val githubOwner: String = "",
    val githubRepo: String = "",
    val githubBaseBranch: String = "main",
) {
    private companion object {
        private const val TAG = "ServerModule"
    }

    val llmConfig = LLMConfig(
        provider = LLM.Provider.GEMINI,
        model = "gemini-2.0-flash",
        apiKey = geminiApiKey,
    )

    val agentFactory = AgentFactory(llmConfig)

    val piiSanitizer = PIISanitizer()

    val dedupCache = LocalDedupCache(maxSize = 1000, ttl = 24.hours)

    val analysisAgent = CrashAnalysisAgent(agentFactory, piiSanitizer, dedupCache)

    /**
     * Notion backend — `null` when `NOTION_API_KEY`/`NOTION_DATABASE_ID` are not
     * set. Lets the server run in GitHub-only / dry-run modes.
     */
    val notionBackend: NotionTicketBackend? =
        if (notionApiKey.isNotBlank() && notionDatabaseId.isNotBlank()) {
            NotionTicketBackend(notionApiKey, notionDatabaseId)
        } else {
            MBALog.w(TAG, "NOTION_API_KEY / NOTION_DATABASE_ID not set — Notion ticketing disabled.")
            null
        }

    /**
     * GitHub auto-fix opener — `null` unless all three of `GITHUB_TOKEN`,
     * `GITHUB_OWNER`, `GITHUB_REPO` are set. Used by the orchestrator when a
     * crash report arrives with `autoFix=true` and severity ∈ {HIGH, CRITICAL}.
     */
    val githubAutoFixOpener: GitHubAutoFixOpener? =
        if (githubToken.isNotBlank() && githubOwner.isNotBlank() && githubRepo.isNotBlank()) {
            GitHubAutoFixOpener(
                token = githubToken,
                owner = githubOwner,
                repo = githubRepo,
                baseBranch = githubBaseBranch,
            )
        } else {
            MBALog.i(TAG, "GitHub auto-fix disabled — set GITHUB_TOKEN/GITHUB_OWNER/GITHUB_REPO to enable.")
            null
        }

    /**
     * Severity gate for the auto-fix path. `MBA_AUTOFIX_ENABLED` master switch
     * is honoured by the legacy `route()` method; the new path uses
     * [SeverityRouter.shouldAutoFix] which gates purely on severity.
     */
    val severityRouter = SeverityRouter()

    val jobStore = JobStore(dataDir)

    val queue = CrashProcessingQueue(jobStore)

    val demoOrchestrator = DemoOrchestrator(
        analysisTool = CrashAnalysisTool { raw -> analysisAgent.process(raw) },
        eventSink = object : DemoEventSink {
            override suspend fun startProcessing(jobId: String) {
                queue.startProcessing(jobId)
            }

            override suspend fun progress(
                jobId: String,
                message: String,
                stage: String,
                level: String,
                metadata: Map<String, String>,
            ) {
                queue.progress(jobId, message, stage, level, metadata)
            }

            override suspend fun complete(jobId: String, artifactUrl: String) {
                queue.complete(jobId, artifactUrl)
            }

            override suspend fun prOpened(jobId: String, prUrl: String) {
                queue.prOpened(jobId, prUrl)
            }

            override suspend fun fail(jobId: String, errorMessage: String) {
                queue.fail(jobId, errorMessage)
            }
        },
        severityRouter = severityRouter,
        notionBackend = notionBackend,
        githubAutoFixTool = githubAutoFixOpener?.let { opener ->
            GitHubAutoFixTool { report -> opener.openAutoFix(report) }
        },
    )

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        FileDedupPersistence.restore(dedupCache, dedupCachePath)
        MBALog.i(
            TAG,
            "ServerModule initialized — " +
                "Notion=${if (notionBackend != null) "enabled" else "disabled"}, " +
                "GitHubAutoFix=${if (githubAutoFixOpener != null) "enabled" else "disabled"}, " +
                "baseBranch=$githubBaseBranch",
        )
    }

    fun shutdown() {
        MBALog.i(TAG, "Shutting down ServerModule...")
        FileDedupPersistence.save(dedupCache, dedupCachePath)
        agentFactory.close()
        notionBackend?.close()
        githubAutoFixOpener?.close()
        scope.cancel()
    }
}

/** Key for accessing ServerModule from ApplicationCall. */
val ApplicationCall.module: ServerModule
    get() = application.attributes[ServerModuleKey]

private val ServerModuleKey = AttributeKey<ServerModule>("ServerModule")

/** Install the ServerModule into the Application. */
fun Application.installServerModule(
    geminiApiKey: String,
    notionApiKey: String,
    notionDatabaseId: String,
    serverApiKey: String,
    dedupCachePath: String,
    dataDir: String = "data",
    githubToken: String = "",
    githubOwner: String = "",
    githubRepo: String = "",
    githubBaseBranch: String = "main",
): ServerModule {
    val module = ServerModule(
        geminiApiKey = geminiApiKey,
        notionApiKey = notionApiKey,
        notionDatabaseId = notionDatabaseId,
        serverApiKey = serverApiKey,
        dedupCachePath = dedupCachePath,
        dataDir = dataDir,
        githubToken = githubToken,
        githubOwner = githubOwner,
        githubRepo = githubRepo,
        githubBaseBranch = githubBaseBranch,
    )
    attributes.put(ServerModuleKey, module)
    return module
}
