package dev.sunnat629.mba.agent.runtime

import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.model.Severity
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FileLocalCrashAggregationStoreTest {
    @Test
    fun `same fingerprint updates existing local group after restart`() = runTest {
        val file = File.createTempFile("mba-agent-store", ".json")
        file.delete()
        val raw = rawReport()
        val report = processedReport(raw)

        val first = FileLocalCrashAggregationStore(file).upsert(raw, report)
        val second = FileLocalCrashAggregationStore(file).upsert(raw.copy(id = "crash-2"), report.copy(raw = raw.copy(id = "crash-2")))

        assertTrue(first.isNewGroup)
        assertFalse(second.isNewGroup)
        assertEquals(first.group.id, second.group.id)
        assertEquals(2, second.group.occurrenceCount)
        assertEquals(1, second.group.uniqueDeviceCount)
    }

    @Test
    fun `external artifact ids persist with local group`() = runTest {
        val file = File.createTempFile("mba-agent-store", ".json")
        file.delete()
        val raw = rawReport()
        val report = processedReport(raw)
        val store = FileLocalCrashAggregationStore(file)
        val first = store.upsert(raw, report)

        store.markNotionSynced(first.group.id, "notion-1", "https://notion.test/1")
        store.markGitHubSynced(first.group.id, "42", "https://github.test/issues/42")

        val second = FileLocalCrashAggregationStore(file).upsert(raw.copy(id = "crash-2"), report.copy(raw = raw.copy(id = "crash-2")))

        assertEquals("notion-1", second.group.notionTicketId)
        assertEquals("https://notion.test/1", second.group.notionUrl)
        assertEquals("42", second.group.githubIssueId)
        assertEquals("https://github.test/issues/42", second.group.githubIssueUrl)
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
        customMetadata = mapOf("appId" to "dev.sample"),
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
