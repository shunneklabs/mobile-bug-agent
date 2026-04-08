package dev.sunnat629.mba.android

import dev.sunnat629.mba.core.config.MBAConfig
import dev.sunnat629.mba.core.store.CrashStore
import dev.sunnat629.mba.core.ticket.TicketBackend

/**
 * Runtime wiring container for Android background processing.
 *
 * Reason: WorkManager instantiates Workers via reflection.
 * For the SDK-only MVP, the host app owns secrets (Notion token/db ids),
 * so the host app must wire in the concrete CrashStore + TicketBackend.
 */
object MBAAndroidRuntime {
    @Volatile var config: MBAConfig? = null
        private set

    @Volatile var crashStore: CrashStore? = null
        private set

    @Volatile var ticketBackend: TicketBackend? = null
        private set

    fun configure(config: MBAConfig, crashStore: CrashStore, ticketBackend: TicketBackend) {
        this.config = config
        this.crashStore = crashStore
        this.ticketBackend = ticketBackend
    }
}
