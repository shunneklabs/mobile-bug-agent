package dev.sunnat629.mba.core.model

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Immutable snapshot captured at crash time.
 * Written to disk synchronously. No AI processing yet.
 * This is the raw material — everything the crash handler can grab
 * before the process dies.
 */
@Serializable
data class RawCrashReport(
    val id: String,
    val timestamp: Instant = Clock.System.now(),
    val exceptionType: String,
    val message: String? = null,
    val stackTrace: String,
    val threadName: String,
    val isFatal: Boolean = true,
    val device: DeviceContext,
    val appVersion: String,
    val buildType: String,
    val currentScreen: String? = null,
    val breadcrumbs: List<String> = emptyList(),
    val customMetadata: Map<String, String> = emptyMap(),
)
