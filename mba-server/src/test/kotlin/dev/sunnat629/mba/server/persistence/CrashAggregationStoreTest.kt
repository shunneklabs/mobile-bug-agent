package dev.sunnat629.mba.server.persistence

import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.model.Severity
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class CrashAggregationStoreTest {
    @Test
    fun `first crash creates one group and one occurrence`() = runBlocking {
        val store = store()
        val raw = rawReport()

        val result = store.upsert("job-1", raw, processedReport(raw))

        assertTrue(result.isNewGroup)
        assertEquals(1, store.getGroups().size)
        assertEquals(1, store.getOccurrences().size)
        assertEquals(1, result.group.occurrenceCount)
        assertEquals(1, result.group.uniqueDeviceCount)
    }

    @Test
    fun `repeated same fingerprint increments existing group`() = runBlocking {
        val store = store()
        val raw = rawReport()
        store.upsert("job-1", raw, processedReport(raw))

        val duplicate = store.upsert("job-2", raw.copy(id = "crash-2"), processedReport(raw.copy(id = "crash-2")))

        assertFalse(duplicate.isNewGroup)
        assertEquals(1, store.getGroups().size)
        assertEquals(2, store.getOccurrences().size)
        assertEquals(2, duplicate.group.occurrenceCount)
        assertEquals(1, duplicate.group.uniqueDeviceCount)
    }

    @Test
    fun `two devices same fingerprint increments unique device count`() = runBlocking {
        val store = store()
        val raw = rawReport()
        store.upsert("job-1", raw, processedReport(raw))

        val secondDevice = raw.copy(
            id = "crash-2",
            device = raw.device.copy(manufacturer = "Samsung", model = "SM-S928B", marketingName = "Galaxy S24 Ultra"),
        )
        val result = store.upsert("job-2", secondDevice, processedReport(secondDevice))

        assertEquals(2, result.group.occurrenceCount)
        assertEquals(2, result.group.uniqueDeviceCount)
    }

    @Test
    fun `same fingerprint in different environment creates separate group`() = runBlocking {
        val store = store()
        val debug = rawReport(buildType = "debug")
        val release = rawReport(buildType = "release")

        val first = store.upsert("job-1", debug, processedReport(debug))
        val second = store.upsert("job-2", release, processedReport(release))

        assertNotEquals(first.group.id, second.group.id)
        assertEquals(2, store.getGroups().size)
    }

    @Test
    fun `koog fields are not overwritten on duplicate unless confidence improves`() = runBlocking {
        val store = store()
        val raw = rawReport()
        store.upsert("job-1", raw, processedReport(raw, confidence = 0.8f, possibleCause = "first cause"))

        val lower = store.upsert("job-2", raw.copy(id = "crash-2"), processedReport(raw.copy(id = "crash-2"), confidence = 0.2f, possibleCause = "bad cause"))
        assertEquals("first cause", lower.group.possibleCause)

        val higher = store.upsert("job-3", raw.copy(id = "crash-3"), processedReport(raw.copy(id = "crash-3"), confidence = 0.95f, possibleCause = "better cause"))
        assertEquals("better cause", higher.group.possibleCause)
    }

    private fun store(): CrashAggregationStore = CrashAggregationStore(createTempDirectory().toString())

    private fun rawReport(buildType: String = "debug"): RawCrashReport = RawCrashReport(
        id = "crash-1",
        exceptionType = "java.lang.NullPointerException",
        message = "boom",
        stackTrace = "java.lang.NullPointerException\n  at dev.sunnat629.StageNpeCrasher.crash(StageNpeCrasher.kt:6)",
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
        buildType = buildType,
    )

    private fun processedReport(
        raw: RawCrashReport,
        confidence: Float = 0.85f,
        possibleCause: String = "cause",
    ): ProcessedCrashReport = ProcessedCrashReport(
        raw = raw,
        fingerprint = "fingerprint-1",
        severity = Severity.MEDIUM,
        confidence = confidence,
        title = "Stage crash",
        description = "Demo crash",
        possibleCause = possibleCause,
        sanitizedStackTrace = raw.stackTrace,
    )
}
