package dev.sunnat629.mba.agent.model

import kotlinx.serialization.Serializable

@Serializable
data class DuplicateCheckResult(
    val isDuplicate: Boolean,
    val matchType: String? = null, // "exact" | "semantic" | null
    val matchedCrashId: String? = null,
    val confidence: Float,
    val reasoning: String,
)
