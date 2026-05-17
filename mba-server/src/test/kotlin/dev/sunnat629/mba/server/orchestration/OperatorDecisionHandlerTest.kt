package dev.sunnat629.mba.server.orchestration

import dev.sunnat629.mba.agent.CrashAnalysisResult
import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.DuplicateCrashReport
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.model.Severity
import dev.sunnat629.mba.core.model.TicketResult
import dev.sunnat629.mba.core.ticket.TicketBackend
import dev.sunnat629.mba.core.ticket.TicketUpdate
import dev.sunnat629.mba.github.AutoFixResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperatorDecisionHandlerTest {
    @Test
    fun `accepts all booth operator decision values`() {
        assertEquals(
            setOf("notion", "github", "both", "autofix", "notify", "fallback"),
            OperatorDecision.allowedWireValues,
        )
        assertEquals(OperatorDecision.GitHub, OperatorDecision.fromWireValue(" github "))
    }

    @Test
    fun `notion decision creates only Notion ticket`() = runBlocking {
        val raw = rawReport()
        val processed = processedReport(raw)
        val sink = RecordingDemoEventSink()
        val notion = RecordingTicketBackend(url = "https://notion.test/ticket-1")
        val github = RecordingTicketBackend(url = "https://github.test/issues/1")

        handler(sink, processed, notion, github).handle("job-1", raw, OperatorDecision.Notion)

        assertEquals(listOf(processed), notion.createdReports)
        assertTrue(github.createdReports.isEmpty())
        assertTrue(sink.events.any { it.type == "complete" && it.message == "https://notion.test/ticket-1" })
    }

    @Test
    fun `github decision creates only GitHub issue`() = runBlocking {
        val raw = rawReport()
        val processed = processedReport(raw)
        val sink = RecordingDemoEventSink()
        val notion = RecordingTicketBackend(url = "https://notion.test/ticket-1")
        val github = RecordingTicketBackend(ticketId = "42", url = "https://github.test/issues/42")

        handler(sink, processed, notion, github).handle("job-2", raw, OperatorDecision.GitHub)

        assertTrue(notion.createdReports.isEmpty())
        assertEquals(listOf(processed), github.createdReports)
        assertTrue(sink.events.any { it.type == "pr" && it.message == "https://github.test/issues/42" })
    }

    @Test
    fun `github decision explains missing GitHub configuration`() = runBlocking {
        val raw = rawReport()
        val processed = processedReport(raw)
        val sink = RecordingDemoEventSink()

        handler(
            sink = sink,
            processed = processed,
            githubConfigMessage = "GitHub is not configured (missing GITHUB_TOKEN, GITHUB_OWNER or GITHUB_TARGET_OWNER)",
        ).handle("job-missing-github", raw, OperatorDecision.GitHub)

        assertTrue(sink.events.any { event ->
            event.stage == "github_pr" &&
                event.level == "warning" &&
                event.message.contains("missing GITHUB_TOKEN") &&
                event.message.contains("GITHUB_TARGET_OWNER")
        })
    }

    @Test
    fun `both decision creates Notion ticket and GitHub issue`() = runBlocking {
        val raw = rawReport()
        val processed = processedReport(raw)
        val sink = RecordingDemoEventSink()
        val notion = RecordingTicketBackend(url = "https://notion.test/ticket-1")
        val github = RecordingTicketBackend(ticketId = "9", url = "https://github.test/issues/9")

        handler(sink, processed, notion, github).handle("job-3", raw, OperatorDecision.Both)

        assertEquals(listOf(processed), notion.createdReports)
        assertEquals(listOf(processed), github.createdReports)
        assertTrue(sink.events.any { it.type == "complete" })
        assertTrue(sink.events.any { it.type == "pr" })
    }

    @Test
    fun `autofix decision starts GitHub issue and branch path`() = runBlocking {
        val raw = rawReport()
        val processed = processedReport(raw)
        val sink = RecordingDemoEventSink()
        var autoFixCalled = false

        handler(
            sink = sink,
            processed = processed,
            autoFixTool = GitHubAutoFixTool {
                autoFixCalled = true
                AutoFixResult.Success(7, "https://github.test/issues/7", "autofix/7")
            },
        ).handle("job-4", raw, OperatorDecision.AutoFix)

        assertTrue(autoFixCalled)
        assertTrue(sink.events.any { it.message.contains("branch 'autofix/7' ready") })
        assertTrue(sink.events.any { it.message.contains("Patch/build/draft PR runner is not wired yet") })
        assertTrue(sink.events.any { it.type == "pr" && it.message == "https://github.test/issues/7" })
    }

    @Test
    fun `github decision creates issue when crash analysis returns duplicate`() = runBlocking {
        val raw = rawReport()
        val sink = RecordingDemoEventSink()
        val github = RecordingTicketBackend(ticketId = "42", url = "https://github.test/issues/42")
        val handler = OperatorDecisionHandler(
            analysisTool = CrashAnalysisTool {
                CrashAnalysisResult.Duplicate(
                    DuplicateCrashReport(
                        fingerprint = "duplicate-fingerprint",
                        newDevice = raw.device,
                        timestamp = raw.timestamp,
                    )
                )
            },
            eventSink = sink,
            notionBackend = null,
            githubIssueBackend = github,
            githubAutoFixTool = null,
            ioDispatcher = Dispatchers.Unconfined,
        )

        handler.handle("job-duplicate-github", raw, OperatorDecision.GitHub)

        assertEquals(1, github.createdReports.size)
        assertEquals("duplicate-fingerprint", github.createdReports.single().fingerprint)
        assertTrue(github.createdReports.single().title.contains(raw.exceptionType))
        assertTrue(sink.events.any { it.message.contains("creating operator ticket from stored raw report") })
        assertTrue(sink.events.any { it.type == "pr" && it.message == "https://github.test/issues/42" })
    }

    @Test
    fun `autofix decision starts GitHub path when crash analysis returns duplicate`() = runBlocking {
        val raw = rawReport()
        val sink = RecordingDemoEventSink()
        var autoFixReport: ProcessedCrashReport? = null
        val handler = OperatorDecisionHandler(
            analysisTool = CrashAnalysisTool {
                CrashAnalysisResult.Duplicate(
                    DuplicateCrashReport(
                        fingerprint = "duplicate-fingerprint",
                        newDevice = raw.device,
                        timestamp = raw.timestamp,
                    )
                )
            },
            eventSink = sink,
            notionBackend = null,
            githubIssueBackend = null,
            githubAutoFixTool = GitHubAutoFixTool { report ->
                autoFixReport = report
                AutoFixResult.Success(7, "https://github.test/issues/7", "autofix/7")
            },
            ioDispatcher = Dispatchers.Unconfined,
        )

        handler.handle("job-duplicate-autofix", raw, OperatorDecision.AutoFix)

        assertEquals("duplicate-fingerprint", autoFixReport?.fingerprint)
        assertTrue(sink.events.any { it.message.contains("creating operator ticket from stored raw report") })
        assertTrue(sink.events.any { it.message.contains("branch 'autofix/7' ready") })
        assertTrue(sink.events.any { it.type == "pr" && it.message == "https://github.test/issues/7" })
    }

    @Test
    fun `notify and fallback do not invoke analysis or backends`() = runBlocking {
        val raw = rawReport()
        val sink = RecordingDemoEventSink()
        var analyzed = false
        val handler = OperatorDecisionHandler(
            analysisTool = CrashAnalysisTool {
                analyzed = true
                CrashAnalysisResult.New(processedReport(raw))
            },
            eventSink = sink,
            notionBackend = RecordingTicketBackend(),
            githubIssueBackend = RecordingTicketBackend(),
            githubAutoFixTool = null,
            ioDispatcher = Dispatchers.Unconfined,
        )

        handler.handle("job-5", raw, OperatorDecision.Notify)
        handler.handle("job-5", raw, OperatorDecision.Fallback)

        assertFalse(analyzed)
        assertTrue(sink.events.any { it.message.contains("Notify-only selected") })
        assertTrue(sink.events.any { it.message.contains("Fallback selected") })
    }

    private fun handler(
        sink: RecordingDemoEventSink,
        processed: ProcessedCrashReport,
        notionBackend: TicketBackend? = null,
        githubIssueBackend: TicketBackend? = null,
        githubConfigMessage: String = "GitHub is not configured for test",
        autoFixTool: GitHubAutoFixTool? = null,
    ): OperatorDecisionHandler = OperatorDecisionHandler(
        analysisTool = CrashAnalysisTool { CrashAnalysisResult.New(processed) },
        eventSink = sink,
        notionBackend = notionBackend,
        githubIssueBackend = githubIssueBackend,
        githubConfigMessage = githubConfigMessage,
        githubAutoFixTool = autoFixTool,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun rawReport(): RawCrashReport = RawCrashReport(
        id = "crash-1",
        exceptionType = "java.lang.NullPointerException",
        message = "boom",
        stackTrace = "java.lang.NullPointerException at StageNpeCrasher.kt:6",
        threadName = "main",
        device = DeviceContext(
            manufacturer = "Google",
            model = "Pixel",
            osVersion = "15",
            sdkInt = 35,
            locale = "en_US",
            totalMemoryMb = 8192,
            availableMemoryMb = 4096,
        ),
        appVersion = "0.1.0-kotlinconf",
        buildType = "debug",
    )

    private fun processedReport(raw: RawCrashReport): ProcessedCrashReport = ProcessedCrashReport(
        raw = raw,
        fingerprint = "fingerprint-1",
        severity = Severity.MEDIUM,
        confidence = 0.95f,
        title = "Stage crash",
        description = "Demo crash",
        sanitizedStackTrace = raw.stackTrace,
    )

    private data class RecordedEvent(
        val type: String,
        val message: String,
        val stage: String = "",
        val level: String = "",
    )

    private class RecordingDemoEventSink : DemoEventSink {
        val events = mutableListOf<RecordedEvent>()

        override suspend fun startProcessing(jobId: String) {
            events += RecordedEvent("start", jobId)
        }

        override suspend fun progress(
            jobId: String,
            message: String,
            stage: String,
            level: String,
            metadata: Map<String, String>,
        ) {
            events += RecordedEvent("progress", message, stage, level)
        }

        override suspend fun complete(jobId: String, artifactUrl: String) {
            events += RecordedEvent("complete", artifactUrl)
        }

        override suspend fun prOpened(jobId: String, prUrl: String) {
            events += RecordedEvent("pr", prUrl)
        }

        override suspend fun fail(jobId: String, errorMessage: String) {
            events += RecordedEvent("fail", errorMessage)
        }
    }

    private class RecordingTicketBackend(
        private val ticketId: String = "ticket-1",
        private val url: String = "https://ticket.test/1",
    ) : TicketBackend {
        val createdReports = mutableListOf<ProcessedCrashReport>()

        override val name: String = "Recording"

        override suspend fun createTicket(report: ProcessedCrashReport): TicketResult {
            createdReports += report
            return TicketResult(ticketId = ticketId, backendName = name, url = url)
        }

        override suspend fun updateTicket(ticketId: String, update: TicketUpdate): TicketResult = TicketResult(
            ticketId = ticketId,
            backendName = name,
        )
    }
}