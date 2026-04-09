package dev.sunnat629.mba.core.model

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * A deduplicated crash group. One group per unique fingerprint.
 * Tracks all devices/versions affected and total occurrence count.
 *
 * Used by [CrashStore] implementations (server-side persistence).
 */
@Serializable
public data class CrashGroup(
    val id: String,
    val fingerprint: String,
    val title: String,
    val severity: Severity,
    val occurrenceCount: Int = 1,
    val affectedDevices: List<String> = emptyList(),
    val affectedOsVersions: List<String> = emptyList(),
    val firstSeen: Instant,
    val lastSeen: Instant,
    val ticketId: String? = null,
)
