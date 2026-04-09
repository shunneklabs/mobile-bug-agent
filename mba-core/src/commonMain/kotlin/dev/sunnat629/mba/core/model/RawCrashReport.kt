package dev.sunnat629.mba.core.model

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Immutable snapshot captured at crash time.
 *
 * **Internal** — external devs never construct this directly.
 * Written to disk synchronously by the crash handler.
 * AI processing happens later in the background.
 */
@Serializable
public data class RawCrashReport(
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
