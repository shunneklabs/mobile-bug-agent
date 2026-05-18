package dev.sunnat629.mba.server.orchestration

import dev.sunnat629.mba.agent.CrashAnalysisResult
import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.model.Severity
import dev.sunnat629.mba.core.model.TicketResult
import dev.sunnat629.mba.core.ticket.TicketBackend
import dev.sunnat629.mba.core.ticket.TicketUpdate
import dev.sunnat629.mba.github.AutoFixResult
import dev.sunnat629.mba.server.persistence.CrashAggregationStore
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class DemoOrchestratorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `dry run completes analysis when Notion skipped and GitHub not requested`() {
        runBlocking {
            val sink = RecordingDemoEventSink()
            val raw = rawReport(autoFix = false, skipNotion = true)
            val processed = processedReport(raw, severity = Severity.MEDIUM)

            orchestrator(
                sink = sink,
                analysisResult = CrashAnalysisResult.New(processed),
                notionBackend = null,
                githubTool = null,
            ).process("job-1", raw)

            assertEquals(listOf("start", "progress", "progress", "progress", "progress", "progress", "complete"), sink.events.map { it.type })
            assertTrue(sink.events.any { it.message.contains("Created bug group") })
            assertTrue(sink.events.any { it.message.contains("Routing severity MEDIUM") })
            assertEquals("analysis://fingerprint-1", sink.events.last().message)
        }
    }

    @Test
    fun `demo npe low route opens github when kill switch enabled and guardrails pass`() {
        runBlocking {
            val sink = RecordingDemoEventSink()
            val raw = rawFixture("demo-npe-low.json")
            val processed = processedReport(raw, severity = Severity.LOW)

            orchestrator(
                sink = sink,
                analysisResult = CrashAnalysisResult.New(processed),
                severityRouter = SeverityRouter(autoFixEnabled = true),
                notionBackend = null,
                githubTool = GitHubAutoFixTool { AutoFixResult.Success(7, "https://github.test/issues/7", "autofix/7") },
            ).process("job-2", raw)

            assertTrue(sink.events.any { it.type == "pr" && it.message == "https://github.test/issues/7" })
            assertTrue(sink.events.any { it.stage == "github_pr" && it.message.contains("Issue #7 opened") })
            assertFalse(sink.events.any { it.type == "fail" })
        }
    }

    @Test
    fun `critical crash route creates notify artifact only and no pr`() {
        runBlocking {
            val sink = RecordingDemoEventSink()
            val raw = rawFixture("critical-crash.json")
            val processed = processedReport(raw, severity = Severity.CRITICAL)
            val notion = RecordingTicketBackend()

            orchestrator(
                sink = sink,
                analysisResult = CrashAnalysisResult.New(processed),
                severityRouter = SeverityRouter(autoFixEnabled = true),
                notionBackend = notion,
                githubTool = GitHubAutoFixTool { AutoFixResult.Success(42, "https://github.test/issues/42", "autofix/42-crash") },
            ).process("job-3", raw)

            assertFalse(sink.events.any { it.type == "pr" })
            assertTrue(sink.events.any { it.level == "warning" && it.message.contains("notify-only") })
            assertEquals(1, notion.createdReports.size)
            assertEquals("https://notion.test/ticket-1", sink.events.last().message)
        }
    }

    @Test
    fun `low route falls back to notify path when guardrail prevents github pr`() {
        runBlocking {
            val sink = RecordingDemoEventSink()
            val raw = rawFixture("low-guardrail-fail.json")
            val processed = processedReport(raw, severity = Severity.LOW)
            val notion = RecordingTicketBackend()

            orchestrator(
                sink = sink,
                analysisResult = CrashAnalysisResult.New(processed),
                severityRouter = SeverityRouter(autoFixEnabled = true),
                notionBackend = notion,
                githubTool = GitHubAutoFixTool { AutoFixResult.Failure("Guardrail failed: base branch 'master' is protected") },
            ).process("job-guardrail", raw)

            assertFalse(sink.events.any { it.type == "pr" })
            assertTrue(sink.events.any { it.stage == "github_pr" && it.message.contains("Guardrail failed") })
            assertEquals(1, notion.createdReports.size)
            assertEquals("https://notion.test/ticket-1", sink.events.last().message)
        }
    }

    @Test
    fun `kill switch disabled prevents pr creation even for low severity`() {
        runBlocking {
            val sink = RecordingDemoEventSink()
            val raw = rawFixture("demo-npe-low.json").copy(skipNotion = false)
            val processed = processedReport(raw, severity = Severity.LOW)
            val notion = RecordingTicketBackend()

            orchestrator(
                sink = sink,
                analysisResult = CrashAnalysisResult.New(processed),
                severityRouter = SeverityRouter(autoFixEnabled = false),
                notionBackend = notion,
                githubTool = GitHubAutoFixTool { AutoFixResult.Success(99, "https://github.test/issues/99", "autofix/99") },
            ).process("job-kill-switch", raw)

            assertFalse(sink.events.any { it.type == "pr" })
            assertTrue(sink.events.any { it.level == "warning" && it.message.contains("MBA_AUTOFIX_ENABLED=false") })
            assertEquals(1, notion.createdReports.size)
        }
    }

    @Test
    fun `notion failure does not hide completed analysis from booth`() {
        runBlocking {
            val sink = RecordingDemoEventSink()
            val raw = rawReport(autoFix = true, skipNotion = false)
            val processed = processedReport(raw, severity = Severity.MEDIUM)
            val notion = RecordingTicketBackend(
                result = TicketResult.failure("Recording", "Notion API 400 : schema mismatch"),
            )

            orchestrator(
                sink = sink,
                analysisResult = CrashAnalysisResult.New(processed),
                notionBackend = notion,
                githubTool = null,
            ).process("job-4", raw)

            assertFalse(sink.events.any { it.type == "fail" })
            assertTrue(sink.events.any { it.level == "warning" && it.message.contains("GitHub backend is not configured") })
            assertTrue(sink.events.any { it.level == "warning" && it.message.contains("Notion ticket failed") })
            assertEquals("analysis://fingerprint-1", sink.events.last().message)
        }
    }

    private fun orchestrator(
        sink: RecordingDemoEventSink,
        analysisResult: CrashAnalysisResult,
        severityRouter: SeverityRouter = SeverityRouter(autoFixEnabled = false),
        notionBackend: TicketBackend?,
        githubTool: GitHubAutoFixTool?,
    ): DemoOrchestrator = DemoOrchestrator(
        analysisTool = CrashAnalysisTool { analysisResult },
        eventSink = sink,
        severityRouter = severityRouter,
        notionBackend = notionBackend,
        githubAutoFixTool = githubTool,
        aggregationStore = CrashAggregationStore(createTempDirectory().toString()),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun rawFixture(name: String): RawCrashReport {
        val resource = javaClass.classLoader.getResource("fixtures/$name") ?: error("Missing fixture $name")
        return json.decodeFromString<RawCrashReport>(resource.readText())
    }

    private fun rawReport(autoFix: Boolean, skipNotion: Boolean): RawCrashReport = RawCrashReport(
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
        autoFix = autoFix,
        skipNotion = skipNotion,
    )

    private fun processedReport(raw: RawCrashReport, severity: Severity): ProcessedCrashReport = ProcessedCrashReport(
        raw = raw,
        fingerprint = "fingerprint-1",
        severity = severity,
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

        override suspend fun complete(jobId: String, artifactUrl: String, artifactType: String) {
            events += RecordedEvent("complete", artifactUrl)
        }

        override suspend fun prOpened(jobId: String, prUrl: String, artifactType: String) {
            events += RecordedEvent("pr", prUrl)
        }

        override suspend fun fail(jobId: String, errorMessage: String) {
            events += RecordedEvent("fail", errorMessage)
        }
    }

    private class RecordingTicketBackend(
        private val result: TicketResult = TicketResult(
            ticketId = "ticket-1",
            backendName = "Recording",
            url = "https://notion.test/ticket-1",
        ),
    ) : TicketBackend {
        val createdReports = mutableListOf<ProcessedCrashReport>()

        override val name: String = "Recording"

        override suspend fun createTicket(report: ProcessedCrashReport): TicketResult {
            createdReports += report
            return result
        }

        override suspend fun updateTicket(ticketId: String, update: TicketUpdate): TicketResult = TicketResult(
            ticketId = ticketId,
            backendName = name,
        )
    }
}
