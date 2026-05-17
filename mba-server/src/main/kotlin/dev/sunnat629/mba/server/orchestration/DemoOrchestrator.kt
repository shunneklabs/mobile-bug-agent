package dev.sunnat629.mba.server.orchestration

import dev.sunnat629.mba.agent.CrashAnalysisResult
import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.ticket.TicketBackend
import dev.sunnat629.mba.github.AutoFixResult
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
    suspend fun complete(jobId: String, artifactUrl: String)
    suspend fun prOpened(jobId: String, prUrl: String)
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
                        "Duplicate crash (fingerprint=${result.report.fingerprint.take(8)}…) — skipping LLM",
                    )
                    MBALog.i(TAG, "Job $jobId: Duplicate: ${result.report.fingerprint}")
                    eventSink.complete(jobId, "duplicate://${result.report.fingerprint}")
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
        eventSink.progress(
            jobId,
            "Routing severity ${processed.severity} (autoFix=${raw.autoFix}, skipNotion=${raw.skipNotion})",
            metadata = mapOf(
                "severity" to processed.severity.name,
                "autoFix" to raw.autoFix.toString(),
                "skipNotion" to raw.skipNotion.toString(),
            ),
        )

        val severityOk = severityRouter.shouldAutoFix(processed.severity)
        val wantGitHub = raw.autoFix && severityOk && githubAutoFixTool != null
        val wantNotion = !raw.skipNotion && notionBackend != null

        if (raw.autoFix && !severityOk) {
            eventSink.progress(
                jobId,
                "autoFix=true ignored — severity ${processed.severity} below HIGH gate",
                level = "warning",
            )
        }
        if (raw.autoFix && githubAutoFixTool == null) {
            eventSink.progress(
                jobId,
                "autoFix=true requested but GitHub backend is not configured (set GITHUB_TOKEN/OWNER/REPO)",
                level = "warning",
            )
        }

        var prOpened = false
        if (wantGitHub) {
            prOpened = openGitHubAutoFix(jobId, processed)
        }

        if (wantNotion) {
            createNotionTicket(jobId, processed, fallback, prOpened)
        } else if (!prOpened) {
            completeDryRun(jobId, raw, processed)
        }
    }

    private suspend fun openGitHubAutoFix(jobId: String, processed: ProcessedCrashReport): Boolean {
        eventSink.progress(jobId, "Opening GitHub issue…", stage = "github_pr")
        return when (val ghResult = withContext(ioDispatcher) { githubAutoFixTool!!.openAutoFix(processed) }) {
            is AutoFixResult.Success -> {
                eventSink.progress(
                    jobId,
                    "Issue #${ghResult.issueNumber} opened — branch '${ghResult.branch}' ready for agent patch",
                    stage = "github_pr",
                )
                eventSink.prOpened(jobId, ghResult.issueUrl)
                true
            }
            is AutoFixResult.IssueOnly -> {
                eventSink.progress(
                    jobId,
                    "Issue #${ghResult.issueNumber} opened but branch creation failed: ${ghResult.branchError}",
                    stage = "github_pr",
                    level = "warning",
                )
                eventSink.prOpened(jobId, ghResult.issueUrl)
                true
            }
            is AutoFixResult.Failure -> {
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

    private suspend fun createNotionTicket(
        jobId: String,
        processed: ProcessedCrashReport,
        fallback: Boolean,
        prOpened: Boolean,
    ) {
        val notionStage = "notion_ticket"
        eventSink.progress(
            jobId,
            if (fallback) "Creating Notion fallback ticket…" else "Creating Notion ticket…",
            stage = notionStage,
        )
        val ticket = withContext(ioDispatcher) { notionBackend!!.createTicket(processed) }
        if (ticket.success) {
            MBALog.i(TAG, "Job $jobId: Notion ticket created: ${ticket.url}")
            eventSink.complete(jobId, ticket.url ?: "notion://created")
        } else {
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
                eventSink.complete(jobId, "analysis://${processed.fingerprint}")
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
        eventSink.complete(jobId, "analysis://${processed.fingerprint}")
    }
}