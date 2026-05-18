package dev.sunnat629.mba.agent

import ai.koog.prompt.llm.LLMCapability
import dev.sunnat629.mba.agent.model.CrashSummary
import dev.sunnat629.mba.agent.model.CombinedCrashAnalysis
import dev.sunnat629.mba.agent.model.ParsedStackTrace
import dev.sunnat629.mba.agent.model.SeverityResult
import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.model.Severity
import dev.sunnat629.mba.core.pii.PIISanitizer
import dev.sunnat629.mba.core.store.LocalDedupCache
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class CrashAnalysisAgentTest {

    private val testDevice = DeviceContext(
        manufacturer = "Google",
        model = "Pixel 8",
        osVersion = "15",
        sdkInt = 35,
        locale = "en-US",
        totalMemoryMb = 8192,
        availableMemoryMb = 4096,
    )

    private val testReport = RawCrashReport(
        id = "test-001",
        exceptionType = "java.lang.NullPointerException",
        message = "Attempt to invoke method on null for user@test.com",
        stackTrace = """
            java.lang.NullPointerException: Attempt to invoke virtual method
            at com.example.app.CheckoutViewModel.processPayment(CheckoutViewModel.kt:87)
            at com.example.app.CheckoutViewModel.onConfirm(CheckoutViewModel.kt:45)
            at android.view.View.performClick(View.java:7448)
        """.trimIndent(),
        threadName = "main",
        isFatal = true,
        device = testDevice,
        appVersion = "1.0.0",
        buildType = "debug",
        currentScreen = "CheckoutScreen",
        breadcrumbs = listOf("opened cart", "tapped checkout"),
    )

    /** Mock executor that returns predictable results without LLM calls. */
    private open class FakeExecutor : CrashAnalysisExecutor {
        var parseCallCount = 0
        var classifyCallCount = 0
        var summaryCallCount = 0

        override suspend fun parseStackTrace(sanitizedTrace: String): ParsedStackTrace {
            parseCallCount++
            return ParsedStackTrace(
                rootException = "java.lang.NullPointerException",
                rootMessage = "Attempt to invoke virtual method",
                crashFile = "CheckoutViewModel.kt",
                crashLine = 87,
                crashMethod = "processPayment",
                isAppCode = true,
            )
        }

        override suspend fun classifySeverity(
            parsed: ParsedStackTrace,
            device: DeviceContext,
        ): SeverityResult {
            classifyCallCount++
            return SeverityResult(
                severity = Severity.HIGH,
                confidence = 0.9f,
                reasoning = "Main thread crash in payment flow",
            )
        }

        override suspend fun generateSummary(
            parsed: ParsedStackTrace,
            severity: SeverityResult,
            screen: String?,
            breadcrumbs: List<String>,
            device: DeviceContext,
            crashContext: String,
        ): CrashSummary {
            summaryCallCount++
            return CrashSummary(
                title = "Checkout crashes during payment",
                description = "NPE in CheckoutViewModel.processPayment",
                stepsToReproduce = "1. Open cart\n2. Tap checkout",
                possibleCause = "Payment response is null",
            )
        }
    }

    /** Fake factory that returns the mock executor. */
    private class FakeAgentFactory(private val executor: CrashAnalysisExecutor) {
        fun create(): CrashAnalysisExecutor = executor
    }

    @Test
    fun newCrashFlowsThroughFullPipeline() = runTest {
        val executor = FakeExecutor()
        val piiSanitizer = PIISanitizer()
        val dedupCache = LocalDedupCache(maxSize = 100, ttl = 24.hours)

        val agent = CrashAnalysisAgent(
            agentFactory = object : AgentFactory(
                llmConfig = dev.sunnat629.mba.core.config.LLMConfig.NONE
            ) {
                override fun create(): CrashAnalysisExecutor = executor
            },
            piiSanitizer = piiSanitizer,
            dedupCache = dedupCache,
        )

        val result = agent.process(testReport)

        assertIs<CrashAnalysisResult.New>(result)
        assertEquals("Checkout crashes during payment", result.report.title)
        assertEquals(Severity.HIGH, result.report.severity)
        assertTrue(result.report.fingerprint.isNotEmpty())
        assertEquals(1, executor.parseCallCount)
        assertEquals(1, executor.classifyCallCount)
        assertEquals(1, executor.summaryCallCount)

        // PII should be scrubbed from message
        assertTrue(!result.report.raw.message!!.contains("user@test.com"))
    }

    @Test
    fun duplicateCrashSkipsLLM() = runTest {
        val executor = FakeExecutor()
        val piiSanitizer = PIISanitizer()
        val dedupCache = LocalDedupCache(maxSize = 100, ttl = 24.hours)

        val agent = CrashAnalysisAgent(
            agentFactory = object : AgentFactory(
                llmConfig = dev.sunnat629.mba.core.config.LLMConfig.NONE
            ) {
                override fun create(): CrashAnalysisExecutor = executor
            },
            piiSanitizer = piiSanitizer,
            dedupCache = dedupCache,
        )

        // First call: new crash
        val first = agent.process(testReport)
        assertIs<CrashAnalysisResult.New>(first)

        // Second call with same report: should be duplicate
        val second = agent.process(testReport)
        assertIs<CrashAnalysisResult.Duplicate>(second)

        // LLM should only have been called once (first time)
        assertEquals(1, executor.parseCallCount)
        assertEquals(1, executor.classifyCallCount)
        assertEquals(1, executor.summaryCallCount)
    }

    @Test
    fun koogPathIsDefault() = runTest {
        val executor = FakeExecutor()

        val factory = object : AgentFactory(
            llmConfig = dev.sunnat629.mba.core.config.LLMConfig.NONE,
        ) {
            override fun create(): CrashAnalysisExecutor = executor
        }

        val created = factory.create()
        assertEquals(executor, created)
    }

    @Test
    fun legacyPathCanBeEnabled() = runTest {
        val executor = FakeExecutor()

        val factory = object : AgentFactory(
            llmConfig = dev.sunnat629.mba.core.config.LLMConfig.NONE,
            useKoog = false,
        ) {
            override fun create(): CrashAnalysisExecutor = executor
        }

        val created = factory.create()
        assertEquals(executor, created)
    }

    @Test
    fun geminiKoogModelUsesKnownCapabilities() {
        val model = koogModelForConfig(
            dev.sunnat629.mba.core.config.LLM.gemini("test-key")
        )

        assertEquals("gemini-2.5-flash", model.id)
        assertTrue(model.supports(LLMCapability.Completion))
    }

    @Test
    fun fallbackUsesRawOnlyWhenAgentFails() = runTest {
        val agent = CrashAnalysisAgent(
            agentFactory = object : AgentFactory(
                llmConfig = dev.sunnat629.mba.core.config.LLMConfig.NONE
            ) {
                override fun create(): CrashAnalysisExecutor = object : CrashAnalysisExecutor {
                    override suspend fun parseStackTrace(sanitizedTrace: String): ParsedStackTrace {
                        error("LLM unavailable")
                    }

                    override suspend fun classifySeverity(
                        parsed: ParsedStackTrace,
                        device: DeviceContext,
                    ): SeverityResult {
                        error("unreachable")
                    }

                    override suspend fun generateSummary(
                        parsed: ParsedStackTrace,
                        severity: SeverityResult,
                        screen: String?,
                        breadcrumbs: List<String>,
                        device: DeviceContext,
                        crashContext: String,
                    ): CrashSummary {
                        error("unreachable")
                    }
                }
            },
            piiSanitizer = PIISanitizer(),
            dedupCache = LocalDedupCache(maxSize = 100, ttl = 24.hours),
        )

        val result = agent.process(testReport)

        assertIs<CrashAnalysisResult.Fallback>(result)
        assertEquals(0.0f, result.report.confidence)
        assertNull(result.report.stepsToReproduce)
        assertNull(result.report.possibleCause)
        assertTrue(result.report.description.contains("Attempt to invoke method on null"))
    }

    @Test
    fun combinedKoogAnalysisPopulatesTicketFieldsInOnePass() = runTest {
        val executor = object : FakeExecutor() {
            override suspend fun analyzeCrash(
                sanitizedTrace: String,
                device: DeviceContext,
                screen: String?,
                breadcrumbs: List<String>,
                crashContext: String,
            ): CombinedCrashAnalysis = CombinedCrashAnalysis(
                rootException = "java.lang.NullPointerException",
                rootMessage = "Attempt to invoke virtual method",
                crashFile = "CheckoutViewModel.kt",
                crashLine = 87,
                crashMethod = "processPayment",
                isAppCode = true,
                severity = "HIGH",
                confidence = 0.86f,
                severityReasoning = "Checkout crash blocks payment",
                title = "Checkout crashes while processing payment",
                description = "NullPointerException in CheckoutViewModel.kt:87 on debug app 1.0.0.",
                stepsToReproduce = "1. Open cart\n2. Tap checkout",
                possibleCause = "Payment response is null before processPayment reads it",
            )
        }
        val agent = CrashAnalysisAgent(
            agentFactory = object : AgentFactory(
                llmConfig = dev.sunnat629.mba.core.config.LLMConfig.NONE
            ) {
                override fun create(): CrashAnalysisExecutor = executor
            },
            piiSanitizer = PIISanitizer(),
            dedupCache = LocalDedupCache(maxSize = 100, ttl = 24.hours),
        )

        val result = agent.process(testReport)

        assertIs<CrashAnalysisResult.New>(result)
        assertEquals("Checkout crashes while processing payment", result.report.title)
        assertEquals(0.86f, result.report.confidence)
        assertEquals("1. Open cart\n2. Tap checkout", result.report.stepsToReproduce)
        assertEquals("Payment response is null before processPayment reads it", result.report.possibleCause)
        assertEquals(0, executor.parseCallCount)
        assertEquals(0, executor.classifyCallCount)
        assertEquals(0, executor.summaryCallCount)
    }
}
