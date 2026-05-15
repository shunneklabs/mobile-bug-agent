package dev.sunnat629.mba.agent.model

import kotlinx.serialization.Serializable

@Serializable
internal data class DuplicateCheckResult(
    val isDuplicate: Boolean,
    val matchType: String? = null,
    val matchedCrashId: String? = null,
    val confidence: Float = 0.0f,
    val reasoning: String = "",
)
