package dev.sunnat629.mba.android

import android.content.Context
import dev.sunnat629.mba.agent.CrashAnalysisAgent
import dev.sunnat629.mba.core.config.MBAConfig
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.store.CrashStore
import dev.sunnat629.mba.core.store.LocalDedupCache
import dev.sunnat629.mba.core.ticket.TicketBackend
import dev.sunnat629.mba.notion.NotionClient
import dev.sunnat629.mba.notion.NotionConfig
import dev.sunnat629.mba.notion.NotionCrashStore
import dev.sunnat629.mba.notion.NotionTicketBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Reads and processes crash files written during previous sessions.
 *
 * MVP behavior:
 * - runs on next launch
 * - processes all crash JSON files under filesDir/mba-crashes
 * - creates/updates Notion crash reports + bug tickets
 */
internal object PendingCrashProcessor {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Process pending crashes.
     *
     * This requires MBAConfig in SDK-only mode so we can access Notion token + db id.
     */
    fun process(context: Context, config: MBAConfig) {
        scope.launch {
            val crashDir = context.filesDir.resolve("mba-crashes")
            if (!crashDir.exists()) return@launch

            val mode = config.mode
            if (mode !is dev.sunnat629.mba.core.config.MBAMode.SdkOnly) return@launch

            val notionConfig = NotionConfig(
                token = mode.ticketBackendToken,
                databaseId = mode.crashDatabaseId,
            )

            val notion = NotionClient(notionConfig)
            val crashStore: CrashStore = NotionCrashStore(notion, notionConfig)
            val ticketBackend: TicketBackend = NotionTicketBackend(notion, notionConfig)

            val agent = CrashAnalysisAgent(
                piiSanitizer = config.piiSanitizer,
                dedupCache = LocalDedupCache(
                    maxSize = config.agentConfig.maxDedupCacheSize,
                    ttl = config.agentConfig.localDedupWindow,
                ),
            )

            crashDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".json") }
                ?.take(config.agentConfig.maxCrashesPerBatch)
                ?.forEach { file ->
                    processOne(file, agent, crashStore, ticketBackend)
                }
        }
    }

    private suspend fun processOne(
        file: File,
        agent: CrashAnalysisAgent,
        crashStore: CrashStore,
        ticketBackend: TicketBackend,
    ) {
        val raw = runCatching { json.decodeFromString(RawCrashReport.serializer(), file.readText()) }.getOrNull()
            ?: return

        val result = agent.process(raw)

        when (result) {
            is dev.sunnat629.mba.agent.CrashAnalysisResult.New -> {
                val group = crashStore.findByFingerprint(result.report.fingerprint)
                    ?: crashStore.insertCrash(result.report)

                val ticket = ticketBackend.createTicket(result.report)
                crashStore.linkTicket(group.id, ticket.ticketId)

                // Success → delete crash file.
                file.delete()
            }

            is dev.sunnat629.mba.agent.CrashAnalysisResult.Duplicate -> {
                val group = crashStore.findByFingerprint(result.fingerprint)
                if (group != null) {
                    crashStore.incrementCount(group.id, raw.device)
                    file.delete()
                }
            }
        }
    }
}
