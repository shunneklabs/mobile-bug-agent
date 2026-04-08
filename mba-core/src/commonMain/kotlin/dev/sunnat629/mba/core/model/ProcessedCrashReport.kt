package dev.sunnat629.mba.core.model

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Output of the on-device Koog agent. Ready for ticket creation.
 * Contains the AI-generated title, description, severity, and root cause hypothesis.
 */
@Serializable
data class ProcessedCrashReport(
    val raw: RawCrashReport,
    val fingerprint: String,
    val severity: Severity,
    val confidence: Float,
    val title: String,
    val description: String,
    val stepsToReproduce: String? = null,
    val possibleCause: String? = null,
    val crashFile: String? = null,
    val crashLine: Int? = null,
    val crashMethod: String? = null,
    val isAppCode: Boolean = false,
    val sanitizedStackTrace: String,
)

/**
 * Returned when local dedup cache detects a known crash fingerprint.
 * No LLM call needed — just update the count.
 */
@Serializable
data class DuplicateCrashReport(
    val fingerprint: String,
    val newDevice: DeviceContext,
    val timestamp: Instant,
)
