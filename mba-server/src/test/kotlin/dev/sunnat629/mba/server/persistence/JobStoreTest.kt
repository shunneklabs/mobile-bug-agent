package dev.sunnat629.mba.server.persistence

import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.RawCrashReport
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class JobStoreTest {
    @Test
    fun `persists raw crash report for delayed operator actions`() = runBlocking {
        val dataDir = Files.createTempDirectory("mba-job-store-test").toFile()
        val raw = rawReport()

        JobStore(dataDir.absolutePath).createJob("job-1", raw)

        val restored = JobStore(dataDir.absolutePath).getJob("job-1")

        assertEquals(raw, restored?.rawReport)
    }

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
}