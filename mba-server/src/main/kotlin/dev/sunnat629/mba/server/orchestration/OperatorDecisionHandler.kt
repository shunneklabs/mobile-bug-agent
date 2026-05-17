package dev.sunnat629.mba.server.orchestration

import dev.sunnat629.mba.agent.CrashAnalysisResult
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.model.Severity
import dev.sunnat629.mba.core.ticket.TicketBackend
import dev.sunnat629.mba.github.AutoFixResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OperatorDecisionHandler(
    private val analysisTool: CrashAnalysisTool,
    private val eventSink: DemoEventSink,
    private val notionBackend: TicketBackend?,
    private val githubIssueBackend: TicketBackend?,
    private val githubConfigMessage: String =
        "GitHub is not configured. Set GITHUB_TOKEN plus GITHUB_OWNER/GITHUB_REPO, or GITHUB_TARGET_OWNER/GITHUB_TARGET_REPO.",
    private val githubAutoFixTool: GitHubAutoFixTool?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun handle(jobId: String, raw: RawCrashReport, decision: OperatorDecision) {
        eventSink.progress(
            jobId,
            "Operator chose ${decision.displayName}",
            stage = "operator_decision",
            metadata = mapOf("decision" to decision.wireValue),
        )

        when (decision) {
            OperatorDecision.Notify -> eventSink.progress(jobId, "Notify-only selected — no ticket backend invoked")
            OperatorDecision.Fallback -> eventSink.progress(
                jobId,
                "Fallback selected — keeping analysis visible for manual handling",
                level = "warning",
            )
            OperatorDecision.Notion -> createNotionTicket(jobId, analyze(jobId, raw))
            OperatorDecision.GitHub -> createGitHubIssue(jobId, analyze(jobId, raw))
            OperatorDecision.Both -> {
                val report = analyze(jobId, raw)
                createNotionTicket(jobId, report)
                createGitHubIssue(jobId, report)
            }
            OperatorDecision.AutoFix -> startAutoFix(jobId, analyze(jobId, raw))
        }
    }

    private suspend fun analyze(jobId: String, raw: RawCrashReport): ProcessedCrashReport {
        eventSink.progress(jobId, "Operator action analyzing crash report…")
        val analysisResult: CrashAnalysisResult = withContext(ioDispatcher) { analysisTool.process(raw) }
        return when (analysisResult) {
            is CrashAnalysisResult.New -> analysisResult.report
            is CrashAnalysisResult.Duplicate -> {
                eventSink.progress(
                    jobId,
                    "Duplicate crash (fingerprint=${analysisResult.report.fingerprint.take(8)}…) — creating operator ticket from stored raw report",
                    level = "warning",
                )
                raw.toOperatorFallbackReport(analysisResult.report.fingerprint)
            }
            is CrashAnalysisResult.Fallback -> {
                eventSink.progress(
                    jobId,
                    "AI analysis failed (${analysisResult.error.message}) — using fallback report for operator action",
                    level = "warning",
                )
                analysisResult.report
            }
        }
    }

    private fun RawCrashReport.toOperatorFallbackReport(fingerprint: String): ProcessedCrashReport =
        ProcessedCrashReport(
            raw = this,
            fingerprint = fingerprint,
            severity = Severity.MEDIUM,
            confidence = 0.0f,
            title = "$exceptionType in ${currentScreen ?: "unknown"}",
            description = "Duplicate crash selected by the operator. Full raw stack trace is attached for ticket creation.",
            sanitizedStackTrace = stackTrace,
        )

    private suspend fun createNotionTicket(jobId: String, report: ProcessedCrashReport) {
        val backend = notionBackend
        if (backend == null) {
            eventSink.progress(jobId, "Notion ticket requested but Notion is not configured", stage = "notion_ticket", level = "warning")
            return
        }

        eventSink.progress(jobId, "Creating Notion ticket from operator action…", stage = "notion_ticket")
        val ticket = withContext(ioDispatcher) { backend.createTicket(report) }
        if (ticket.success) {
            eventSink.complete(jobId, ticket.url ?: "notion://created")
        } else {
            eventSink.progress(
                jobId,
                "Notion ticket failed: ${ticket.errorMessage ?: "Unknown Notion error"}",
                stage = "notion_ticket",
                level = "error",
            )
        }
    }

    private suspend fun createGitHubIssue(jobId: String, report: ProcessedCrashReport) {
        val backend = githubIssueBackend
        if (backend == null) {
            eventSink.progress(jobId, "GitHub issue requested but $githubConfigMessage", stage = "github_pr", level = "warning")
            return
        }

        eventSink.progress(jobId, "Creating GitHub issue from operator action…", stage = "github_pr")
        val issue = withContext(ioDispatcher) { backend.createTicket(report) }
        if (issue.success) {
            eventSink.progress(jobId, "GitHub issue #${issue.ticketId} created", stage = "github_pr")
            eventSink.prOpened(jobId, issue.url ?: "github://issue/${issue.ticketId}")
        } else {
            eventSink.progress(
                jobId,
                "GitHub issue failed: ${issue.errorMessage ?: "Unknown GitHub error"}",
                stage = "github_pr",
                level = "error",
            )
        }
    }

    private suspend fun startAutoFix(jobId: String, report: ProcessedCrashReport) {
        val tool = githubAutoFixTool
        if (tool == null) {
            eventSink.progress(jobId, "Autofix requested but $githubConfigMessage", stage = "github_pr", level = "warning")
            return
        }

        eventSink.progress(jobId, "Autofix: creating GitHub issue + branch…", stage = "github_pr")
        when (val result = withContext(ioDispatcher) { tool.openAutoFix(report) }) {
            is AutoFixResult.Success -> {
                eventSink.progress(
                    jobId,
                    "Autofix issue #${result.issueNumber} opened — branch '${result.branch}' ready for agent patch",
                    stage = "github_pr",
                )
                eventSink.progress(
                    jobId,
                    "Patch/build/draft PR runner is not wired yet; stopping before CI/PR creation",
                    stage = "github_pr",
                    level = "warning",
                )
                eventSink.prOpened(jobId, result.issueUrl)
            }
            is AutoFixResult.IssueOnly -> {
                eventSink.progress(
                    jobId,
                    "Autofix issue #${result.issueNumber} opened but branch creation failed: ${result.branchError}",
                    stage = "github_pr",
                    level = "warning",
                )
                eventSink.prOpened(jobId, result.issueUrl)
            }
            is AutoFixResult.Failure -> eventSink.progress(
                jobId,
                "Autofix failed: ${result.reason}",
                stage = "github_pr",
                level = "error",
            )
        }
    }
}

enum class OperatorDecision(val wireValue: String, val displayName: String) {
    Notion("notion", "NOTION TICKET"),
    GitHub("github", "GITHUB ISSUE"),
    Both("both", "BOTH"),
    AutoFix("autofix", "AUTOFIX"),
    Notify("notify", "NOTIFY"),
    Fallback("fallback", "FALLBACK"),
    ;

    companion object {
        fun fromWireValue(value: String): OperatorDecision? = entries.firstOrNull { it.wireValue == value.trim().lowercase() }

        val allowedWireValues: Set<String> = entries.mapTo(linkedSetOf()) { it.wireValue }
    }
}