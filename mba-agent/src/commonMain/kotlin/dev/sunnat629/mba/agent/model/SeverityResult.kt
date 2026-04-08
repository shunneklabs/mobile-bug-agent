package dev.sunnat629.mba.agent.model

import dev.sunnat629.mba.core.model.Severity
import kotlinx.serialization.Serializable

@Serializable
data class SeverityResult(
    val severity: Severity,
    val confidence: Float,
    val reasoning: String,
)
