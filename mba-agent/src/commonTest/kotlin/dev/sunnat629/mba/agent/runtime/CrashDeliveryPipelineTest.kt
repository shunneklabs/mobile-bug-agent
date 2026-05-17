package dev.sunnat629.mba.agent.runtime

import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.model.TicketResult
import dev.sunnat629.mba.core.store.LocalDedupCache
import dev.sunnat629.mba.core.ticket.TicketBackend
import dev.sunnat629.mba.core.ticket.TicketUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CrashDeliveryPipelineTest {

    @Test
    fun backendAcceptedSkipsFallbackTicketCreation() = runTest {
        val ticketBackend = RecordingTicketBackend()
        val pipeline = CrashDeliveryPipeline(
            rawUploader = StaticRawUploader(RawCrashUploadResult.Accepted("job-123", "queued")),
            fallbackTicketBackend = ticketBackend,
        )

        val result = pipeline.process(rawReport())

        assertTrue(result.success)
        assertEquals(CrashDeliveryChannel.BACKEND, result.channel)
        assertEquals("job-123", result.backendJobId)
        assertEquals(0, ticketBackend.createdCount)
    }

    @Test
    fun backendExceptionFallsBackToLocalTicketCreation() = runTest {
        val ticketBackend = RecordingTicketBackend()
        val pipeline = CrashDeliveryPipeline(
            rawUploader = ThrowingRawUploader(),
            fallbackTicketBackend = ticketBackend,
        )

        val result = pipeline.process(rawReport())

        assertTrue(result.success)
        assertEquals(CrashDeliveryChannel.FALLBACK_TICKET, result.channel)
        assertEquals(1, ticketBackend.createdCount)
    }

    @Test
    fun fallbackTicketDedupsRepeatedCrash() = runTest {
        val ticketBackend = RecordingTicketBackend()
        val pipeline = CrashDeliveryPipeline(
            fallbackTicketBackend = ticketBackend,
            fallbackDedupCache = LocalDedupCache(),
        )

        val first = pipeline.process(rawReport())
        val second = pipeline.process(rawReport(id = "crash-2"))

        assertEquals(CrashDeliveryChannel.FALLBACK_TICKET, first.channel)
        assertEquals(CrashDeliveryChannel.DUPLICATE, second.channel)
        assertEquals(1, ticketBackend.createdCount)
    }

    private class StaticRawUploader(
        private val result: RawCrashUploadResult,
    ) : RawCrashUploader {
        override suspend fun upload(rawReport: RawCrashReport): RawCrashUploadResult = result
    }

    private class ThrowingRawUploader : RawCrashUploader {
        override suspend fun upload(rawReport: RawCrashReport): RawCrashUploadResult =
            error("backend unavailable")
    }

    private class RecordingTicketBackend : TicketBackend {
        var createdCount = 0
            private set

        override val name: String = "Recording"

        override suspend fun createTicket(report: ProcessedCrashReport): TicketResult {
            createdCount++
            return TicketResult(
                ticketId = "ticket-$createdCount",
                backendName = name,
                success = true,
            )
        }

        override suspend fun updateTicket(ticketId: String, update: TicketUpdate): TicketResult =
            TicketResult(ticketId = ticketId, backendName = name, success = true)
    }

    private fun rawReport(id: String = "crash-1"): RawCrashReport = RawCrashReport(
        id = id,
        exceptionType = "java.lang.IllegalStateException",
        message = "boom",
        stackTrace = """
            java.lang.IllegalStateException: boom
                at com.example.CheckoutViewModel.submit(CheckoutViewModel.kt:42)
                at com.example.CheckoutScreen.onPay(CheckoutScreen.kt:21)
        """.trimIndent(),
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
        appVersion = "1.0",
        buildType = "debug",
        currentScreen = "Checkout",
    )
}
