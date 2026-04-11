package dev.sunnat629.mba.core.store

import dev.sunnat629.mba.core.model.CrashGroup
import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.ProcessedCrashReport

/**
 * Server-side crash persistence and deduplication.
 *
 * **Internal** — external devs don't interact with this.
 * MVP: NotionCrashStore. Phase 2: PostgresCrashStore.
 *
 * Contract: implementations must be thread-safe.
 */
internal interface CrashStore {

    /** Find existing crash group by fingerprint. Null = new crash. */
    suspend fun findByFingerprint(fingerprint: String): CrashGroup?

    /** Create a new crash group. Returns group with assigned ID. */
    suspend fun insertCrash(report: ProcessedCrashReport): CrashGroup

    /** Increment occurrence count and add device info. */
    suspend fun incrementCount(groupId: String, device: DeviceContext)

    /** Link a ticket to a crash group after ticket creation. */
    suspend fun linkTicket(groupId: String, ticketId: String)
}
