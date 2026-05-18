package dev.sunnat629.mba.agent.runtime

import dev.sunnat629.mba.agent.CrashAnalysisAgent
import dev.sunnat629.mba.agent.CrashAnalysisResult
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.processing.CrashReportBuilder

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
        val analysis = when (val result = analysisAgent.process(raw)) {
            is CrashAnalysisResult.New -> AnalysisOutput(
                report = result.report,
                agentic = true,
                source = "KOOG",
            )
            is CrashAnalysisResult.Fallback -> AnalysisOutput(
                report = result.report,
                agentic = false,
                source = "RAW_FALLBACK",
                error = result.error.message,
            )
            is CrashAnalysisResult.Duplicate -> AnalysisOutput(
                report = raw.toDuplicateFallbackReport(result.report.fingerprint),
                agentic = false,
                source = "LOCAL_DUPLICATE",
            )
        }
        val report = analysis.report
        val aggregation = aggregationStore.upsert(report.raw, report)
        var event = aggregation.toEvent(
            raw = report.raw,
            report = report,
            agentic = analysis.agentic,
            analysisSource = analysis.source,
            analysisError = analysis.error,
        )

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

    private fun LocalAggregationResult.toEvent(
        raw: RawCrashReport,
        report: ProcessedCrashReport,
        agentic: Boolean,
        analysisSource: String,
        analysisError: String?,
    ): MBAAgentEvent =
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
            agentic = agentic,
            analysisSource = analysisSource,
            analysisError = analysisError,
        )

    private fun RawCrashReport.toDuplicateFallbackReport(fingerprint: String): ProcessedCrashReport =
        CrashReportBuilder.build(this).copy(
            fingerprint = fingerprint,
            confidence = 0.0f,
            description = "Repeated crash occurrence grouped by fingerprint.",
        )

    private data class AnalysisOutput(
        val report: ProcessedCrashReport,
        val agentic: Boolean,
        val source: String,
        val error: String? = null,
    )
}
