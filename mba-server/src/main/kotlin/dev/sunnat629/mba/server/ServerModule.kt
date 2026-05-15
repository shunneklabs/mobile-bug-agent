package dev.sunnat629.mba.server

import dev.sunnat629.mba.agent.AgentFactory
import dev.sunnat629.mba.agent.CrashAnalysisAgent
import dev.sunnat629.mba.core.config.LLM
import dev.sunnat629.mba.core.config.LLMConfig
import dev.sunnat629.mba.core.pii.PIISanitizer
import dev.sunnat629.mba.core.store.LocalDedupCache
import dev.sunnat629.mba.notion.NotionTicketBackend
import dev.sunnat629.mba.server.persistence.JobStore
import dev.sunnat629.mba.server.queue.CrashProcessingQueue
import io.ktor.server.application.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
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
) {
    private companion object {
        private val logger = LoggerFactory.getLogger("ServerModule")
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

    val notionBackend = NotionTicketBackend(notionApiKey, notionDatabaseId)

    val jobStore = JobStore(dataDir)

    val queue = CrashProcessingQueue(jobStore)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        FileDedupPersistence.restore(dedupCache, dedupCachePath)
        logger.info("ServerModule initialized — dedup cache restored, queue ready")
    }

    fun shutdown() {
        logger.info("Shutting down ServerModule...")
        FileDedupPersistence.save(dedupCache, dedupCachePath)
        agentFactory.close()
        notionBackend.close()
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
): ServerModule {
    val module = ServerModule(
        geminiApiKey = geminiApiKey,
        notionApiKey = notionApiKey,
        notionDatabaseId = notionDatabaseId,
        serverApiKey = serverApiKey,
        dedupCachePath = dedupCachePath,
        dataDir = dataDir,
    )
    attributes.put(ServerModuleKey, module)
    return module
}
