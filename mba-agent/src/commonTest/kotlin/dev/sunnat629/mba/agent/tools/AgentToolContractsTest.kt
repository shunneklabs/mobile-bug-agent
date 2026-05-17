package dev.sunnat629.mba.agent.tools

import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.model.Severity
import dev.sunnat629.mba.core.model.TicketResult
import dev.sunnat629.mba.core.ticket.TicketBackend
import dev.sunnat629.mba.core.ticket.TicketUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AgentToolContractsTest {
    @Test
    fun `ticket tools return structured success and failure`() = runTest {
        val report = processedReport()
        val notion = CreateNotionTicketTool(RecordingTicketBackend("Notion", TicketResult("notion-1", "Notion", "https://notion.test/1")))
        val github = CreateGitHubIssueTool(RecordingTicketBackend("GitHub", TicketResult.failure("GitHub", "missing token")))

        val notionResult = notion.execute(CreateTicketInput(report))
        val githubResult = github.execute(CreateTicketInput(report))

        assertTrue(notionResult.success)
        assertEquals("https://notion.test/1", notionResult.value?.url)
        assertFalse(githubResult.success)
        assertEquals("ticket_failed", githubResult.failure?.code)
        assertEquals("missing token", githubResult.failure?.message)
    }

    @Test
    fun `read source file tool returns structured source and validates ranges`() = runTest {
        val tool = ReadSourceFileTool { input -> "source for ${input.path}:${input.startLine}-${input.endLine}" }

        val result = tool.execute(ReadSourceFileInput("CrashTestScreen.kt", startLine = 10, endLine = 12))
        val invalid = tool.execute(ReadSourceFileInput("CrashTestScreen.kt", startLine = 12, endLine = 10))

        assertTrue(result.success)
        assertEquals("source for CrashTestScreen.kt:10-12", result.value?.content)
        assertFalse(invalid.success)
        assertEquals("invalid_line_range", invalid.failure?.code)
    }

    @Test
    fun `known demo npe produces deterministic patch proposal`() {
        val source = ReadSourceFileOutput(
            path = "mba-sample/src/main/kotlin/dev/sunnat629/mba/sample/CrashTestScreen.kt",
            content = """
                triggerAndSend("NullPointerException", "💥") {
                    val s: String? = null; s!!.length
                }
            """.trimIndent(),
        )

        val result = SuggestFixTool().execute(SuggestFixInput(processedReport(), source))

        assertTrue(result.success)
        val proposal = assertNotNull(result.value)
        assertTrue(proposal.deterministic)
        assertTrue("checkNotNull(s)" in proposal.newContent)
        assertFalse("s!!.length" in proposal.newContent)
    }

    @Test
    fun `guardrails block protected branch dependency public api and large diffs`() {
        val oldContent = "fun existing() = Unit\n"
        val newContent = buildString {
            appendLine("fun existing() = Unit")
            appendLine("public fun newApi() = Unit")
            appendLine("implementation(\"demo:new-dependency:1.0\")")
            repeat(21) { appendLine("val changed$it = $it") }
        }
        val proposal = FixProposal("build.gradle.kts", oldContent, newContent, changedLines = 23, deterministic = true, rationale = "test")

        val result = RunGuardrailsTool().execute(RunGuardrailsInput(proposal, baseBranch = "master"))

        assertTrue(result.success)
        val report = assertNotNull(result.value)
        assertFalse(report.allowed)
        assertTrue(report.reasons.any { "protected" in it })
        assertTrue(report.reasons.any { "new dependency" in it })
        assertTrue(report.reasons.any { "public API" in it })
        assertTrue(report.reasons.any { "exceeds" in it })
    }

    @Test
    fun `link back tool surfaces ticket issue and pr urls`() {
        val result = LinkPullRequestBackTool().execute(
            LinkPullRequestBackInput(
                ticketUrl = "https://notion.test/ticket",
                issueUrl = "https://github.test/issues/1",
                prUrl = "https://github.test/pull/2",
            )
        )

        assertTrue(result.success)
        assertEquals("https://notion.test/ticket", result.value?.links?.get("notion_ticket"))
        assertEquals("https://github.test/issues/1", result.value?.links?.get("github_issue"))
        assertEquals("https://github.test/pull/2", result.value?.links?.get("pull_request"))
    }

    private fun processedReport(severity: Severity = Severity.LOW): ProcessedCrashReport {
        val raw = RawCrashReport(
            id = "demo-npe-low",
            exceptionType = "java.lang.NullPointerException",
            message = "demo NPE",
            stackTrace = "java.lang.NullPointerException\n\tat dev.sunnat629.mba.sample.CrashTestScreenKt.CrashTestScreen(CrashTestScreen.kt:154)",
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
            currentScreen = "CrashTestScreen",
            autoFix = true,
            skipNotion = true,
        )
        return ProcessedCrashReport(
            raw = raw,
            fingerprint = "demo-fingerprint",
            severity = severity,
            confidence = 0.95f,
            title = "NullPointerException in CrashTestScreen",
            description = "Known deterministic demo NPE",
            crashFile = "CrashTestScreen.kt",
            crashLine = 154,
            crashMethod = "CrashTestScreen",
            isAppCode = true,
            sanitizedStackTrace = raw.stackTrace,
        )
    }

    private class RecordingTicketBackend(
        override val name: String,
        private val result: TicketResult,
    ) : TicketBackend {
        override suspend fun createTicket(report: ProcessedCrashReport): TicketResult = result
        override suspend fun updateTicket(ticketId: String, update: TicketUpdate): TicketResult = result
    }
}