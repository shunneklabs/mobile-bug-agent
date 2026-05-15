package dev.sunnat629.mba.agent.model

import kotlinx.serialization.Serializable

@Serializable
internal data class CrashSummary(
    val title: String,
    val description: String,
    val stepsToReproduce: String? = null,
    val possibleCause: String? = null,
)
