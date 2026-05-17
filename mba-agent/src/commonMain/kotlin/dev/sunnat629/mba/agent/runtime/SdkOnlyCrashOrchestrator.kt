package dev.sunnat629.mba.agent.runtime

import dev.sunnat629.mba.agent.CrashAnalysisAgent
import dev.sunnat629.mba.agent.CrashAnalysisResult
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.model.Severity

public class SdkOnlyCrashOrchestrator(
    private val analysisAgent: CrashAnalysisAgent,
    private val aggregationStore: LocalCrashAggregationStore,
    private val callback: MBAAgentCallback? = null,
    private val notionSink: MBAAgentSink? = null,
    private val githubSink: MBAAgentSink? = null,
    private val skipNotion: Boolean = false,
    private val skipGitIssue: Boolean = true,
) {
    public suspend fun process(raw: RawCrashReport): MBAAgentEvent {
        val report = when (val result = analysisAgent.process(raw)) {
            is CrashAnalysisResult.New -> result.report
            is CrashAnalysisResult.Fallback -> result.report
            is CrashAnalysisResult.Duplicate -> raw.toDuplicateFallbackReport(result.report.fingerprint)
        }
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

        if (!skipGitIssue && aggregation.isNewGroup) {
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
            mode = "SDK_ONLY",
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
        )

    private fun RawCrashReport.toDuplicateFallbackReport(fingerprint: String): ProcessedCrashReport =
        ProcessedCrashReport(
            raw = this,
            fingerprint = fingerprint,
            severity = Severity.MEDIUM,
            confidence = 0.0f,
            title = "${exceptionType.substringAfterLast(".")} in ${currentScreen ?: "unknown screen"}",
            description = "Repeated crash occurrence grouped by fingerprint.",
            stepsToReproduce = breadcrumbs.takeIf { it.isNotEmpty() }
                ?.mapIndexed { index, breadcrumb -> "${index + 1}. $breadcrumb" }
                ?.joinToString("\n"),
            possibleCause = "$exceptionType${message?.let { ": $it" } ?: ""}",
            sanitizedStackTrace = stackTrace,
        )
}
