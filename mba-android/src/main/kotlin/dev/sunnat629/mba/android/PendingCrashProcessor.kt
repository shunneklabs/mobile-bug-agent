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
            processInternal(context, config, crashStore, ticketBackend)
        }
    }

    /**
     * Internal suspendable entry point used by WorkManager.
     */
    suspend fun processInternal(
        context: Context,
        config: MBAConfig,
        crashStore: CrashStore,
        ticketBackend: TicketBackend,
    ) {
        val crashDir = context.filesDir.resolve("mba-crashes")
        if (!crashDir.exists()) return

        val badDir = crashDir.resolve("bad")
        badDir.mkdirs()

        val agent = CrashAnalysisAgent(
            piiSanitizer = config.piiSanitizer,
            dedupCache = LocalDedupCache(
                maxSize = config.agentConfig.maxDedupCacheSize,
                ttl = config.agentConfig.localDedupWindow,
            ),
        )

        crashDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedBy { it.lastModified() }
            ?.take(config.agentConfig.maxCrashesPerBatch)
            ?.forEach { file ->
                runCatching {
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
        }.getOrElse {
            // Corrupt JSON: move aside so we don't loop forever.
            val badDir = file.parentFile?.resolve("bad") ?: return
            badDir.mkdirs()
            file.renameTo(badDir.resolve(file.name))
            return
        }

        val result = agent.process(raw)

        when (result) {
            is dev.sunnat629.mba.agent.CrashAnalysisResult.New -> {
                val group = crashStore.findByFingerprint(result.report.fingerprint)
                    ?: crashStore.insertCrash(result.report)

                val ticket = ticketBackend.createTicket(result.report)
                crashStore.linkTicket(group.id, ticket.ticketId)

                file.delete() // delete only after successful Notion sync
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
