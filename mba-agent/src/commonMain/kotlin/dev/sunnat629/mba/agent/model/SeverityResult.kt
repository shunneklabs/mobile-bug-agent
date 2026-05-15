package dev.sunnat629.mba.agent.model

import dev.sunnat629.mba.core.model.Severity
import kotlinx.serialization.Serializable

@Serializable
internal data class SeverityResult(
    val severity: Severity = Severity.MEDIUM,
    val confidence: Float = 0.5f,
    val reasoning: String = "",
)
