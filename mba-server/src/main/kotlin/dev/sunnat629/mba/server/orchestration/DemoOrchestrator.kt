package dev.sunnat629.mba.server.orchestration

import dev.sunnat629.mba.agent.CrashAnalysisResult
import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.model.Severity
import dev.sunnat629.mba.core.ticket.TicketBackend
import dev.sunnat629.mba.core.ticket.TicketUpdate
import dev.sunnat629.mba.github.AutoFixResult
import dev.sunnat629.mba.notion.NotionTicketBackend
import dev.sunnat629.mba.server.model.BugGroup
import dev.sunnat629.mba.server.model.CrashAggregationUpsert
import dev.sunnat629.mba.server.persistence.CrashAggregationStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "DemoOrchestrator"

interface DemoEventSink {
    suspend fun startProcessing(jobId: String)
    suspend fun progress(
        jobId: String,
        message: String,
        stage: String = "analyzing",
        level: String = "info",
        metadata: Map<String, String> = emptyMap(),
    )
    suspend fun complete(jobId: String, artifactUrl: String, artifactType: String = "artifact")
    suspend fun prOpened(jobId: String, prUrl: String, artifactType: String = "pull_request")
    suspend fun fail(jobId: String, errorMessage: String)
}

fun interface GitHubAutoFixTool {
    suspend fun openAutoFix(report: ProcessedCrashReport): AutoFixResult
}

fun interface CrashAnalysisTool {
    suspend fun process(raw: RawCrashReport): CrashAnalysisResult
}

/**
 * Workstream D v1 orchestrator for the booth pipeline.
 * Owns visible crash lifecycle after `/report` enqueue:
 * analysis → routing → GitHub/Notion tool calls → terminal event.
 */
class DemoOrchestrator(
    private val analysisTool: CrashAnalysisTool,
    private val eventSink: DemoEventSink,
    private val severityRouter: SeverityRouter,
    private val notionBackend: TicketBackend?,
    private val githubAutoFixTool: GitHubAutoFixTool?,
    private val aggregationStore: CrashAggregationStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun process(jobId: String, raw: RawCrashReport) {
        try {
            MBALog.i(TAG, "Job $jobId: Processing ${raw.exceptionType}")
            eventSink.startProcessing(jobId)
            eventSink.progress(jobId, "Sanitizing PII + computing fingerprint…")

            when (val result = withContext(ioDispatcher) { analysisTool.process(raw) }) {
                is CrashAnalysisResult.Duplicate -> {
                    eventSink.progress(
                        jobId,
                        "Duplicate crash (fingerprint=${result.report.fingerprint.take(8)}…) — updating grouped bug",
                    )
                    MBALog.i(TAG, "Job $jobId: Duplicate: ${result.report.fingerprint}")
                    routeProcessedReport(
                        jobId = jobId,
                        raw = raw,
                        processed = raw.toFallbackReport(result.report.fingerprint),
                        fallback = true,
                    )
                }

                is CrashAnalysisResult.New -> {
                    eventSink.progress(
                        jobId,
                        "Analysis complete — severity=${result.report.severity}, " +
                            "confidence=${"%.0f".format(result.report.confidence * 100)}%",
                    )
                    routeProcessedReport(jobId, raw, result.report, fallback = false)
                }

                is CrashAnalysisResult.Fallback -> {
                    eventSink.progress(
                        jobId,
                        "AI analysis failed (${result.error.message}) — using fallback report",
                        level = "warning",
                    )
                    MBALog.w(TAG, "Job $jobId: AI analysis failed, using fallback… (${result.error.message})")
                    routeProcessedReport(jobId, raw, result.report, fallback = true)
                }
            }
        } catch (e: Exception) {
            MBALog.e(TAG, "Job $jobId: Processing failed", e)
            eventSink.fail(jobId, e.message ?: "Unknown error")
        }
    }

    private suspend fun routeProcessedReport(
        jobId: String,
        raw: RawCrashReport,
        processed: ProcessedCrashReport,
        fallback: Boolean,
    ) {
        val upsert = aggregationStore.upsert(jobId, raw, processed)
        eventSink.progress(
            jobId,
            if (upsert.isNewGroup) {
                "Created bug group ${upsert.group.id} (occurrences=1)"
            } else {
                "Updated bug group ${upsert.group.id} (occurrences=${upsert.group.occurrenceCount}, uniqueDevices=${upsert.group.uniqueDeviceCount})"
            },
            metadata = mapOf(
                "bugGroupId" to upsert.group.id,
                "fingerprint" to upsert.group.fingerprint,
                "occurrenceCount" to upsert.group.occurrenceCount.toString(),
            ),
        )

        eventSink.progress(
            jobId,
            "Routing severity ${processed.severity} (autoFix=${raw.autoFix}, skipNotion=${raw.skipNotion})",
            metadata = mapOf(
                "severity" to processed.severity.name,
                "autoFix" to raw.autoFix.toString(),
                "skipNotion" to raw.skipNotion.toString(),
            ),
        )

        val severityOk = severityRouter.route(processed.severity) == RoutingDecision.AutoFix
        val wantGitHub = raw.autoFix && severityOk && githubAutoFixTool != null
        val wantNotion = !raw.skipNotion && notionBackend != null

        if (raw.autoFix && !severityOk) {
            MBALog.w(TAG, "Job $jobId: autoFix ignored because severity ${processed.severity} is not eligible or auto-fix is disabled")
            eventSink.progress(
                jobId,
                "autoFix=true ignored — severity ${processed.severity} is notify-only or MBA_AUTOFIX_ENABLED=false",
                level = "warning",
            )
        }
        if (raw.autoFix && githubAutoFixTool == null) {
            MBALog.w(TAG, "Job $jobId: autoFix requested but GitHub backend is not configured")
            eventSink.progress(
                jobId,
                "autoFix=true requested but GitHub backend is not configured (set GITHUB_TOKEN/OWNER/REPO)",
                level = "warning",
            )
        }

        var prOpened = false
        if (wantGitHub) {
            prOpened = openGitHubAutoFix(jobId, processed, upsert.group)
        }

        if (wantNotion) {
            createOrUpdateNotionTicket(jobId, processed, upsert, fallback, prOpened)
        } else if (!prOpened) {
            completeDryRun(jobId, raw, processed)
        }
    }

    private suspend fun openGitHubAutoFix(jobId: String, processed: ProcessedCrashReport, group: BugGroup): Boolean {
        if (group.githubIssueUrl != null) {
            eventSink.progress(jobId, "GitHub issue already exists for grouped bug", stage = "github_pr")
            eventSink.prOpened(jobId, group.githubIssueUrl, artifactType = "github_issue")
            return true
        }

        eventSink.progress(jobId, "Opening GitHub issue…", stage = "github_pr")
        return when (val ghResult = withContext(ioDispatcher) { githubAutoFixTool!!.openAutoFix(processed) }) {
            is AutoFixResult.Success -> {
                aggregationStore.markGitHubSynced(group.id, ghResult.issueNumber.toString(), ghResult.issueUrl)
                eventSink.progress(
                    jobId,
                    "Issue #${ghResult.issueNumber} opened — branch '${ghResult.branch}' ready for agent patch",
                    stage = "github_pr",
                )
                eventSink.prOpened(jobId, ghResult.issueUrl, artifactType = "github_issue")
                true
            }
            is AutoFixResult.IssueOnly -> {
                aggregationStore.markGitHubSynced(group.id, ghResult.issueNumber.toString(), ghResult.issueUrl)
                eventSink.progress(
                    jobId,
                    "Issue #${ghResult.issueNumber} opened but branch creation failed: ${ghResult.branchError}",
                    stage = "github_pr",
                    level = "warning",
                )
                eventSink.prOpened(jobId, ghResult.issueUrl, artifactType = "github_issue")
                true
            }
            is AutoFixResult.Failure -> {
                aggregationStore.markGitHubFailed(group.id)
                eventSink.progress(
                    jobId,
                    "GitHub auto-fix failed: ${ghResult.reason}",
                    stage = "github_pr",
                    level = "error",
                )
                false
            }
        }
    }

    private suspend fun createOrUpdateNotionTicket(
        jobId: String,
        processed: ProcessedCrashReport,
        upsert: CrashAggregationUpsert,
        fallback: Boolean,
        prOpened: Boolean,
    ) {
        val notionStage = "notion_ticket"
        val existingNotionUrl = upsert.group.notionUrl
        val existingNotionTicketId = upsert.group.notionTicketId
        if (existingNotionUrl != null && existingNotionTicketId != null) {
            eventSink.progress(jobId, "Updating existing Notion grouped bug…", stage = notionStage)
            val latestGroup = aggregationStore.getGroup(upsert.group.id) ?: upsert.group
            val update = groupTicketUpdate(latestGroup)
            val ticket = withContext(ioDispatcher) { notionBackend!!.updateTicket(existingNotionTicketId, update) }
            (notionBackend as? NotionTicketBackend)?.let { backend ->
                withContext(ioDispatcher) { backend.createCrashOccurrence(processed, existingNotionTicketId) }
            }
            if (ticket.success) {
                eventSink.complete(jobId, existingNotionUrl, artifactType = "notion_ticket")
            } else {
                eventSink.progress(
                    jobId,
                    "Notion grouped bug update failed: ${ticket.errorMessage ?: "Unknown Notion error"}",
                    stage = notionStage,
                    level = "warning",
                )
                eventSink.complete(jobId, existingNotionUrl, artifactType = "notion_ticket")
            }
            return
        }

        eventSink.progress(
            jobId,
            if (fallback) "Creating Notion fallback ticket…" else "Creating Notion ticket…",
            stage = notionStage,
        )
        val ticket = withContext(ioDispatcher) { notionBackend!!.createTicket(processed) }
        if (ticket.success) {
            aggregationStore.markNotionSynced(upsert.group.id, ticket.ticketId, ticket.url)
            val githubUrl = aggregationStore.getGroup(upsert.group.id)?.githubIssueUrl
            if (githubUrl != null) {
                withContext(ioDispatcher) {
                    notionBackend!!.updateTicket(
                        ticket.ticketId,
                        groupTicketUpdate(upsert.group).copy(githubIssueUrl = githubUrl, notionUrl = ticket.url),
                    )
                }
            }
            MBALog.i(TAG, "Job $jobId: Notion ticket created: ${ticket.url}")
            eventSink.complete(jobId, ticket.url ?: "notion://created", artifactType = "notion_ticket")
        } else {
            aggregationStore.markNotionFailed(upsert.group.id)
            val msg = ticket.errorMessage ?: "Unknown Notion error"
            MBALog.e(TAG, "Job $jobId: Notion failed: $msg")
            if (prOpened) {
                eventSink.progress(jobId, "Notion ticket failed: $msg", stage = notionStage, level = "warning")
            } else {
                eventSink.progress(
                    jobId,
                    "Notion ticket failed: $msg — keeping analysis result visible",
                    stage = notionStage,
                    level = "warning",
                )
                eventSink.complete(jobId, "analysis://${processed.fingerprint}", artifactType = "analysis")
            }
        }
    }

    private suspend fun completeDryRun(
        jobId: String,
        raw: RawCrashReport,
        processed: ProcessedCrashReport,
    ) {
        val reason = when {
            raw.skipNotion && notionBackend == null -> "skipNotion=true, Notion not configured"
            raw.skipNotion -> "skipNotion=true"
            notionBackend == null -> "Notion not configured"
            else -> "no backend ran"
        }
        eventSink.progress(jobId, "Skipping ticket creation ($reason)")
        eventSink.complete(jobId, "analysis://${processed.fingerprint}", artifactType = "analysis")
    }

    private fun groupTicketUpdate(group: BugGroup): TicketUpdate = TicketUpdate(
        incrementCount = true,
        newOccurrenceTime = group.lastSeen,
        occurrenceCount = group.occurrenceCount,
        uniqueDeviceCount = group.uniqueDeviceCount,
        deviceMatrix = group.deviceMatrix.joinToString("\n") {
            "${it.displayName} / Android ${it.osVersion} API ${it.sdkInt} / app ${it.appVersion} (${it.occurrences}x)"
        },
        githubIssueUrl = group.githubIssueUrl,
        notionUrl = group.notionUrl,
    )

    private fun RawCrashReport.toFallbackReport(fingerprint: String): ProcessedCrashReport =
        ProcessedCrashReport(
            raw = this,
            fingerprint = fingerprint,
            severity = Severity.MEDIUM,
            confidence = 0.0f,
            title = "$exceptionType in ${currentScreen ?: "unknown"}",
            description = "Crash occurrence grouped by fingerprint. AI enrichment was skipped or unavailable for this duplicate.",
            sanitizedStackTrace = stackTrace,
        )
}
