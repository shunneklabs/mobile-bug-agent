package dev.sunnat629.mba.github

import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.RawCrashReport
import dev.sunnat629.mba.core.model.Severity
import dev.sunnat629.mba.core.ticket.TicketUpdate
import dev.sunnat629.mba.github.model.GitHubIssueRequest
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubIssueBackendTest {

    private val testDevice = DeviceContext(
        manufacturer = "Google",
        model = "Pixel 8",
        osVersion = "15",
        sdkInt = 35,
        locale = "en-US",
        totalMemoryMb = 8192,
        availableMemoryMb = 4096,
    )

    private val testReport = ProcessedCrashReport(
        raw = RawCrashReport(
            id = "test-001",
            exceptionType = "java.lang.NullPointerException",
            message = "Attempt to invoke method on null",
            stackTrace = """
                java.lang.NullPointerException: Attempt to invoke virtual method
                at com.example.app.CheckoutViewModel.processPayment(CheckoutViewModel.kt:87)
            """.trimIndent(),
            threadName = "main",
            isFatal = true,
            device = testDevice,
            appVersion = "1.0.0",
            buildType = "debug",
            currentScreen = "CheckoutScreen",
            breadcrumbs = listOf("opened cart", "tapped checkout"),
        ),
        fingerprint = "abc123def456",
        severity = Severity.HIGH,
        confidence = 0.9f,
        title = "Checkout crashes during payment",
        description = "NPE in CheckoutViewModel.processPayment at line 87",
        stepsToReproduce = "1. Open cart\n2. Tap checkout",
        possibleCause = "Payment response is null",
        crashFile = "CheckoutViewModel.kt",
        crashLine = 87,
        crashMethod = "processPayment",
        isAppCode = true,
        sanitizedStackTrace = """
            java.lang.NullPointerException: Attempt to invoke virtual method
            at com.example.app.CheckoutViewModel.processPayment(CheckoutViewModel.kt:87)
        """.trimIndent(),
    )

    @Test
    fun issueBodyContainsAllRequiredSections() = runTest {
        var capturedIssue: GitHubIssueRequest? = null
        val json = Json { ignoreUnknownKeys = true }
        val mockEngine = MockEngine { request ->
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/repos/test-owner/test-repo/issues" ->
                    respond(
                        content = """[]""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                request.method == HttpMethod.Post && request.url.encodedPath == "/repos/test-owner/test-repo/issues" -> {
                    capturedIssue = json.decodeFromString(String(request.body.toByteArray()))
                    respond(
                        content = """{"id":1,"number":42,"html_url":"https://github.com/test/test/issues/42","title":"test","state":"open"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> respond("unexpected: ${request.method.value} ${request.url.encodedPath}", HttpStatusCode.NotFound)
            }
        }
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

        val backend = GitHubIssueBackend("fake-token", "test-owner", "test-repo", httpClient)
        val result = backend.createTicket(testReport)

        assertTrue(result.success)
        val body = capturedIssue?.body.orEmpty()
        assertTrue(body.contains("### Possible Cause"), body)
        assertTrue(body.contains("Payment response is null"), body)
        assertTrue(body.contains("### Steps to Reproduce"), body)
        assertTrue(body.contains("1. Open cart"), body)
        assertTrue(body.contains("2. Tap checkout"), body)

        httpClient.close()
        backend.close()
    }

    @Test
    fun severityLabelsAreCorrect() = runTest {
        val mockEngine = MockEngine { request ->
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/repos/test-owner/test-repo/issues" ->
                    respond(
                        content = """[]""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                request.method == HttpMethod.Post && request.url.encodedPath == "/repos/test-owner/test-repo/issues" ->
                    respond(
                        content = """{"id":1,"number":42,"html_url":"https://github.com/test/test/issues/42","title":"test","state":"open"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                else -> respond("unexpected: ${request.method.value} ${request.url.encodedPath}", HttpStatusCode.NotFound)
            }
        }

        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val backend = GitHubIssueBackend("fake-token", "test-owner", "test-repo", httpClient)
        val result = backend.createTicket(testReport)

        assertTrue(result.success)
        assertEquals("42", result.ticketId)
        assertEquals("https://github.com/test/test/issues/42", result.url)

        httpClient.close()
        backend.close()
    }

    @Test
    fun failureReturnsErrorNotThrow() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = """{"message":"Not Found"}""",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val backend = GitHubIssueBackend("fake-token", "test-owner", "test-repo", httpClient)
        val result = backend.createTicket(testReport)

        assertTrue(!result.success)
        assertTrue(result.errorMessage?.contains("GitHub API") == true)

        httpClient.close()
        backend.close()
    }

    @Test
    fun createTicketReusesOpenIssueWithSameFingerprint() = runTest {
        var postCalled = false
        val mockEngine = MockEngine { request ->
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/repos/test-owner/test-repo/issues" ->
                    respond(
                        content = """[{
                            "id":1,
                            "number":42,
                            "html_url":"https://github.com/test/test/issues/42",
                            "title":"existing",
                            "state":"open",
                            "body":"**Fingerprint:** `abc123def456`"
                        }]""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                request.method == HttpMethod.Post -> {
                    postCalled = true
                    respond("unexpected post", HttpStatusCode.InternalServerError)
                }
                else -> respond("unexpected: ${request.method.value} ${request.url.encodedPath}", HttpStatusCode.NotFound)
            }
        }

        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val backend = GitHubIssueBackend("fake-token", "test-owner", "test-repo", httpClient)
        val result = backend.createTicket(testReport)

        assertTrue(result.success)
        assertEquals("42", result.ticketId)
        assertFalse(postCalled)

        httpClient.close()
        backend.close()
    }

    @Test
    fun updateTicketIncludesFallbackSectionsEvenWhenConfidenceIsZero() = runTest {
        var capturedIssue: GitHubIssueRequest? = null
        val json = Json { ignoreUnknownKeys = true }
        val mockEngine = MockEngine { request ->
            assertEquals("/repos/test-owner/test-repo/issues/42", request.url.encodedPath)
            assertEquals(HttpMethod.Patch, request.method)
            capturedIssue = json.decodeFromString(String(request.body.toByteArray()))
            respond(
                content = """{"id":1,"number":42,"html_url":"https://github.com/test/test/issues/42","title":"test","state":"open"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
        }
        val fallbackReport = testReport.copy(
            confidence = 0.0f,
            description = "Raw fallback report with stack-derived fields.",
        )

        val backend = GitHubIssueBackend("fake-token", "test-owner", "test-repo", httpClient)
        val result = backend.updateTicket(
            ticketId = "42",
            update = TicketUpdate(
                occurrenceCount = 2,
                uniqueDeviceCount = 1,
                report = fallbackReport,
            ),
        )

        assertTrue(result.success)
        val body = capturedIssue?.body.orEmpty()
        assertFalse(body.contains("confidence: 0%"), body)
        assertFalse(body.contains("### Possible Cause"), body)
        assertFalse(body.contains("Payment response is null"), body)
        assertFalse(body.contains("### Steps to Reproduce"), body)
        assertFalse(body.contains("1. Open cart"), body)
        assertTrue(body.contains("**Occurrences:** 2"), body)
        assertTrue(body.contains("NullPointerException"), body)

        httpClient.close()
        backend.close()
    }
}
