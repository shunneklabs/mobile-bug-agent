package dev.sunnat629.mba.server.queue

import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.server.persistence.JobStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class CrashProcessingQueueTest {
    @Test
    fun `enqueue emits rich queued event contract`() {
        runBlocking {
            val dataDir = createTempDataDir("mba-queue-test")
            val queue = CrashProcessingQueue(jobStore = JobStore(dataDir.absolutePath))

            val event = coroutineScope {
                val eventDeferred = async { queue.events.first() }
                queue.enqueue(jobId = "job-123", report = demoReport("job-123"))
                eventDeferred.await()
            }

            assertEquals("job-123", event.jobId)
            assertEquals("job", event.type)
            assertEquals("queued", event.stage)
            assertEquals("Crash report queued", event.message)
            assertEquals("info", event.level)
            assertNotNull(event.timestamp)

            dataDir.deleteRecursively()
        }
    }

    @Test
    fun `publishBoothEvent emits operator event metadata`() {
        runBlocking {
            val dataDir = createTempDataDir("mba-booth-event-test")
            val queue = CrashProcessingQueue(jobStore = JobStore(dataDir.absolutePath))

            val event = coroutineScope {
                val eventDeferred = async { queue.events.first() }
                queue.publishBoothEvent(
                    type = "operator_decision",
                    message = "Operator chose NOTIFY",
                    metadata = mapOf("decision" to "notify", "jobId" to "job-42"),
                    jobId = "job-42",
                )
                eventDeferred.await()
            }

            assertEquals("operator_decision", event.type)
            assertEquals("operator_decision", event.stage)
            assertEquals("notify", event.metadata["decision"])
            assertEquals("job-42", event.jobId)

            dataDir.deleteRecursively()
        }
    }

    @Test
    fun `events broadcast to multiple booth subscribers`() {
        runBlocking {
            val dataDir = createTempDataDir("mba-booth-broadcast-test")
            val queue = CrashProcessingQueue(jobStore = JobStore(dataDir.absolutePath))

            val events = coroutineScope {
                val firstBooth = async { queue.events.first() }
                val secondBooth = async { queue.events.first() }
                queue.enqueue(jobId = "job-broadcast", report = demoReport("job-broadcast"))
                firstBooth.await() to secondBooth.await()
            }

            assertEquals("job-broadcast", events.first.jobId)
            assertEquals("job-broadcast", events.second.jobId)
            assertEquals("Crash report queued", events.first.message)
            assertEquals("Crash report queued", events.second.message)

            dataDir.deleteRecursively()
        }
    }

    @Test
    fun `late booth subscriber receives recent queued event`() {
        runBlocking {
            val dataDir = createTempDataDir("mba-booth-replay-test")
            val queue = CrashProcessingQueue(jobStore = JobStore(dataDir.absolutePath))

            queue.enqueue(jobId = "job-replay", report = demoReport("job-replay"))
            val event = queue.events.first()

            assertEquals("job-replay", event.jobId)
            assertEquals("queued", event.stage)
            assertEquals("Crash report queued", event.message)

            dataDir.deleteRecursively()
        }
    }

    private fun demoReport(id: String): RawCrashReport = RawCrashReport(
        id = id,
        exceptionType = "java.lang.NullPointerException",
        message = "Deterministic booth crash",
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

    private fun createTempDataDir(prefix: String): File {
        val dir = kotlin.io.path.createTempDirectory(prefix).toFile()
        dir.mkdirs()
        return dir
    }
}
