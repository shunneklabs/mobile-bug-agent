package dev.sunnat629.mba.sample

import android.app.Application
import dev.sunnat629.mba.android.CrashWorkScheduler
import dev.sunnat629.mba.android.MBAAndroid
import dev.sunnat629.mba.android.MBAAndroidRuntime
import dev.sunnat629.mba.android.PendingCrashProcessor
import dev.sunnat629.mba.core.MBA
import dev.sunnat629.mba.core.config.MBAConfig
import dev.sunnat629.mba.core.config.MBAMode
import dev.sunnat629.mba.notion.NotionClient
import dev.sunnat629.mba.notion.NotionConfig
import dev.sunnat629.mba.notion.NotionCrashStore
import dev.sunnat629.mba.notion.NotionTicketBackend

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Phase 1: install crash capture early
        MBAAndroid.install(this)

        // Phase 2: configure after installation
        val notionToken = BuildConfig.NOTION_TOKEN
        val crashDb = BuildConfig.NOTION_CRASH_DB_ID_OR_URL
        val ticketDb = BuildConfig.NOTION_TICKET_DB_ID_OR_URL
        val geminiKey = BuildConfig.GEMINI_API_KEY

        val config = MBAConfig.Builder().apply {
            mode = MBAMode.SdkOnly(
                llmApiKey = geminiKey,
                ticketBackend = object : dev.sunnat629.mba.core.ticket.TicketBackend {
                    override val name: String = "Notion"
                    private val notion = NotionClient(NotionConfig(token = notionToken, databaseId = ticketDb))
                    private val backend = NotionTicketBackend(notion, NotionConfig(token = notionToken, databaseId = ticketDb))
                    override suspend fun createTicket(report: dev.sunnat629.mba.core.model.ProcessedCrashReport) = backend.createTicket(report)
                    override suspend fun updateTicket(ticketId: String, update: dev.sunnat629.mba.core.ticket.TicketUpdate) = backend.updateTicket(ticketId, update)
                }
            )
        }.build()

        MBA.configure(config)

        val crashStoreConfig = NotionConfig(token = notionToken, databaseId = crashDb)
        val crashStore = NotionCrashStore(NotionClient(crashStoreConfig), crashStoreConfig)

        val ticketConfig = NotionConfig(token = notionToken, databaseId = ticketDb)
        val ticketBackend = NotionTicketBackend(NotionClient(ticketConfig), ticketConfig)

        MBAAndroidRuntime.configure(config, crashStore, ticketBackend)

        // Process on next launch (immediately, plus also schedule background retry)
        PendingCrashProcessor.process(this, config, crashStore, ticketBackend)
        CrashWorkScheduler.enqueue(this)
    }
}
