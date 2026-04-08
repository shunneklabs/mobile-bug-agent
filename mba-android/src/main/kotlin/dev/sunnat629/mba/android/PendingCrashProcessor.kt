package dev.sunnat629.mba.android

import android.content.Context
import dev.sunnat629.mba.agent.CrashAnalysisAgent
import dev.sunnat629.mba.core.config.MBAConfig
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.store.CrashStore
import dev.sunnat629.mba.core.store.LocalDedupCache
import dev.sunnat629.mba.core.ticket.TicketBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Reads and processes crash files written during previous sessions.
 *
 * MVP behavior (SDK-only + Notion):
 * - run on next launch (or scheduled background work)
 * - process crash JSON files under filesDir/mba-crashes
 * - analyze via [CrashAnalysisAgent]
 * - persist via [CrashStore] and create tickets via [TicketBackend]
 *
 * NOTE: Notion tokens/db-ids are app-owned secrets.
 * The host app must construct and pass in CrashStore/TicketBackend instances.
 */
internal object PendingCrashProcessor {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun process(
        context: Context,
        config: MBAConfig,
        crashStore: CrashStore,
        ticketBackend: TicketBackend,
    ) {
        scope.launch {
            val crashDir = context.filesDir.resolve("mba-crashes")
            if (!crashDir.exists()) return@launch

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
        val raw: RawCrashReport = runCatching {
            json.decodeFromString(RawCrashReport.serializer(), file.readText())
        }.getOrNull() ?: return

        val result = agent.process(raw)

        when (result) {
            is dev.sunnat629.mba.agent.CrashAnalysisResult.New -> {
                val group = crashStore.findByFingerprint(result.report.fingerprint)
                    ?: crashStore.insertCrash(result.report)

                val ticket = ticketBackend.createTicket(result.report)
                crashStore.linkTicket(group.id, ticket.ticketId)

                file.delete() // success
            }

            is dev.sunnat629.mba.agent.CrashAnalysisResult.Duplicate -> {
                val group = crashStore.findByFingerprint(result.fingerprint)
                if (group != null) {
                    crashStore.incrementCount(group.id, raw.device)
                    file.delete() // success
                }
            }
        }
    }
}
