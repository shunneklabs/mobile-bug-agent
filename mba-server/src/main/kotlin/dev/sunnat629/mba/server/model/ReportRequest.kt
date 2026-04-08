package dev.sunnat629.mba.server.model

import dev.sunnat629.mba.core.model.RawCrashReport
import kotlinx.serialization.Serializable

@Serializable
data class ReportRequest(
    val crash: RawCrashReport,
)
