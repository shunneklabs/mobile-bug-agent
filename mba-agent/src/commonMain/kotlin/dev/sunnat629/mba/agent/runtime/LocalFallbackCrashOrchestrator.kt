package dev.sunnat629.mba.agent.runtime

import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.processing.CrashReportBuilder

/**
 * Non-agentic SDKOnly path.
 *
 * Used when the app disables Koog/LLM analysis, omits an LLM key, or wants a
 * raw-derived callback/ticket payload. The event still includes the full raw
 * crash snapshot and device/app metadata.
 */
public class LocalFallbackCrashOrchestrator(
    private val aggregationStore: LocalCrashAggregationStore,
    private val callback: MBAAgentCallback? = null,
    private val notionSink: MBAAgentSink? = null,
    private val githubSink: MBAAgentSink? = null,
    private val skipNotion: Boolean = false,
    private val skipGitIssue: Boolean = true,
) {
    public suspend fun process(raw: RawCrashReport): MBAAgentEvent {
        val report = CrashReportBuilder.build(raw).copy(
            confidence = 0.0f,
            description = raw.rawSummary(),
            stepsToReproduce = null,
            possibleCause = null,
        )
        val aggregation = aggregationStore.upsert(report.raw, report)
        var event = aggregation.toEvent(report.raw, report)

        callback?.onCrashAnalyzed(event)

        if (!skipNotion) {
            val notionResult = notionSink?.sync(event)
            if (notionResult?.success == true && notionResult.ticketId != null) {
                aggregationStore.markNotionSynced(event.group.id, notionResult.ticketId, notionResult.url)
                event = event.copy(
                    externalState = event.externalState.copy(
                        notionTicketId = notionResult.ticketId,
                        notionUrl = notionResult.url,
                    ),
                )
            }
        }

        if (!skipGitIssue && (aggregation.isNewGroup || event.group.githubIssueId != null)) {
            val githubResult = githubSink?.sync(event)
            if (githubResult?.success == true && githubResult.ticketId != null) {
                aggregationStore.markGitHubSynced(event.group.id, githubResult.ticketId, githubResult.url)
                event = event.copy(
                    externalState = event.externalState.copy(
                        githubIssueId = githubResult.ticketId,
                        githubIssueUrl = githubResult.url,
                    ),
                )
            }
        }

        return event
    }

    private fun LocalAggregationResult.toEvent(raw: RawCrashReport, report: ProcessedCrashReport): MBAAgentEvent =
        MBAAgentEvent(
            mode = "SDK_ONLY_FALLBACK",
            group = group,
            occurrence = occurrence,
            report = report,
            raw = raw,
            externalState = ExternalArtifactState(
                notionTicketId = group.notionTicketId,
                notionUrl = group.notionUrl,
                githubIssueId = group.githubIssueId,
                githubIssueUrl = group.githubIssueUrl,
            ),
            isNewGroup = isNewGroup,
            agentic = false,
            analysisSource = "RAW_FALLBACK",
        )

    private fun RawCrashReport.rawSummary(): String =
        message
            ?.takeIf { it.isNotBlank() }
            ?: stackTrace.lineSequence().firstOrNull()
                ?.takeIf { it.isNotBlank() }
            ?: exceptionType
}
