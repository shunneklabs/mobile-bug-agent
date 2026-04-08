package dev.sunnat629.mba.server.model

import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.TicketResult
import kotlinx.serialization.Serializable

@Serializable
data class ReportResponse(
    val processed: ProcessedCrashReport,
    val ticket: TicketResult,
)
