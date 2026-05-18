package dev.sunnat629.mba.agent.runtime

import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.model.Severity
import dev.sunnat629.mba.core.model.TicketResult
import dev.sunnat629.mba.core.ticket.CrashOccurrenceTicketBackend
import dev.sunnat629.mba.core.ticket.TicketBackend
import dev.sunnat629.mba.core.ticket.TicketUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest

class TicketBackendAgentSinkTest {

    @Test
    fun newNotionGroupCreatesParentTicket() = runTest {
        val backend = RecordingOccurrenceBackend("Notion")
        val sink = TicketBackendAgentSink(backend)

        val result = sink.sync(event(isNewGroup = true))

        assertTrue(result.success)
        assertTrue(result.created)
        assertEquals("notion-ticket-1", result.ticketId)
        assertEquals(1, backend.createdTickets)
        assertEquals(0, backend.updatedTickets)
        assertEquals(0, backend.createdOccurrences)
    }

    @Test
    fun duplicateNotionGroupUpdatesParentAndCreatesOccurrence() = runTest {
        val backend = RecordingOccurrenceBackend("Notion")
        val sink = TicketBackendAgentSink(backend)

        val result = sink.sync(
            event(
                isNewGroup = false,
                notionTicketId = "notion-parent-1",
                occurrenceCount = 2,
            )
        )

        assertTrue(result.success)
        assertFalse(result.created)
        assertEquals("notion-parent-1", result.ticketId)
        assertEquals(0, backend.createdTickets)
        assertEquals(1, backend.updatedTickets)
        assertEquals(1, backend.createdOccurrences)
        assertEquals(2, backend.lastUpdate?.occurrenceCount)
    }

    @Test
    fun newGithubGroupCreatesIssue() = runTest {
        val backend = RecordingTicketBackend("GitHub")
        val sink = TicketBackendAgentSink(backend)

        val result = sink.sync(event(isNewGroup = true))

        assertTrue(result.success)
        assertTrue(result.created)
        assertEquals("github-ticket-1", result.ticketId)
        assertEquals(1, backend.createdTickets)
        assertEquals(0, backend.updatedTickets)
    }

    private open class RecordingTicketBackend(
        override val name: String,
    ) : TicketBackend {
        var createdTickets = 0
            private set
        var updatedTickets = 0
            private set
        var lastUpdate: TicketUpdate? = null
            private set

        override suspend fun createTicket(report: ProcessedCrashReport): TicketResult {
            createdTickets++
            return TicketResult(
                ticketId = "${name.lowercase()}-ticket-$createdTickets",
                backendName = name,
                url = "https://${name.lowercase()}.test/$createdTickets",
                success = true,
            )
        }

        override suspend fun updateTicket(ticketId: String, update: TicketUpdate): TicketResult {
            updatedTickets++
            lastUpdate = update
            return TicketResult(
                ticketId = ticketId,
                backendName = name,
                url = "https://${name.lowercase()}.test/$ticketId",
                success = true,
            )
        }
    }

    private class RecordingOccurrenceBackend(name: String) :
        RecordingTicketBackend(name),
        CrashOccurrenceTicketBackend {

        var createdOccurrences = 0
            private set

        override suspend fun createCrashOccurrence(
            report: ProcessedCrashReport,
            parentBugTicketId: String,
        ): TicketResult {
            createdOccurrences++
            return TicketResult(
                ticketId = "occurrence-$createdOccurrences",
                backendName = name,
                success = true,
            )
        }
    }

    private fun event(
        isNewGroup: Boolean,
        notionTicketId: String? = null,
        githubIssueId: String? = null,
        occurrenceCount: Int = 1,
    ): MBAAgentEvent {
        val now = Clock.System.now()
        val raw = rawReport()
        return MBAAgentEvent(
            mode = "SDK_ONLY",
            group = LocalBugGroup(
                id = "bug-1",
                appId = "sample",
                environment = "debug",
                fingerprint = "fingerprint-1",
                occurrenceCount = occurrenceCount,
                uniqueDeviceCount = 1,
                firstSeen = now,
                lastSeen = now,
                notionTicketId = notionTicketId,
                githubIssueId = githubIssueId,
            ),
            occurrence = LocalCrashOccurrence(
                id = raw.id,
                bugGroupId = "bug-1",
                fingerprint = "fingerprint-1",
                deviceIdHash = "device-1",
                occurredAt = now,
                appVersion = raw.appVersion,
                osVersion = raw.device.osVersion,
                deviceModel = raw.device.displayName,
                screen = raw.currentScreen,
            ),
            report = processedReport(raw),
            raw = raw,
            externalState = ExternalArtifactState(
                notionTicketId = notionTicketId,
                githubIssueId = githubIssueId,
            ),
            isNewGroup = isNewGroup,
        )
    }

    private fun rawReport(): RawCrashReport = RawCrashReport(
        id = "crash-1",
        exceptionType = "java.lang.NullPointerException",
        message = "boom",
        stackTrace = "java.lang.NullPointerException\n\tat dev.sample.Foo.bar(Foo.kt:12)",
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
        appVersion = "1.2.3",
        buildType = "debug",
        currentScreen = "Checkout",
    )

    private fun processedReport(raw: RawCrashReport): ProcessedCrashReport = ProcessedCrashReport(
        raw = raw,
        fingerprint = "fingerprint-1",
        severity = Severity.MEDIUM,
        confidence = 0.9f,
        title = "Foo crashes",
        description = "Demo crash",
        sanitizedStackTrace = raw.stackTrace,
    )
}
