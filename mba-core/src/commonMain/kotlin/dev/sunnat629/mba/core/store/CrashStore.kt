package dev.sunnat629.mba.core.store

import dev.sunnat629.mba.core.model.CrashGroup
import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.ProcessedCrashReport

/**
 * Where crash reports are persisted and deduplicated.
 * MVP: NotionCrashStore (Notion API).
 * Phase 2: PostgresCrashStore (Exposed + PostgreSQL).
 *
 * Implementations must be thread-safe (called from WorkManager background thread).
 */
interface CrashStore {

    /** Find existing crash group by fingerprint. Returns null if this is a new crash. */
    suspend fun findByFingerprint(fingerprint: String): CrashGroup?

    /** Create a new crash group from a processed report. Returns the group with its assigned ID. */
    suspend fun insertCrash(report: ProcessedCrashReport): CrashGroup

    /** Increment occurrence count and add device info to an existing crash group. */
    suspend fun incrementCount(groupId: String, device: DeviceContext)

    /** Link a ticket to a crash group after ticket creation. */
    suspend fun linkTicket(groupId: String, ticketId: String)
}
