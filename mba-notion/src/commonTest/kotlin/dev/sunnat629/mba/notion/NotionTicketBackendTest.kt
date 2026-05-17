package dev.sunnat629.mba.notion

import dev.sunnat629.mba.core.model.*
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class NotionTicketBackendTest {

    private val testDevice = DeviceContext(
        manufacturer = "Google",
        model = "Pixel 8",
        osVersion = "15",
        sdkInt = 35,
        locale = "en-US",
        totalMemoryMb = 8192,
        availableMemoryMb = 4096,
    )

    private val testRawReport = RawCrashReport(
        id = "test-001",
        exceptionType = "NullPointerException",
        stackTrace = "at com.example.Foo.bar(Foo.kt:42)",
        threadName = "main",
        device = testDevice,
        appVersion = "1.0.0",
        buildType = "debug",
    )

    private val testReport = ProcessedCrashReport(
        raw = testRawReport,
        fingerprint = "abc123",
        severity = Severity.HIGH,
        confidence = 0.9f,
        title = "Checkout crash on payment",
        description = "NPE in Foo.bar",
        possibleCause = "Null response from API",
        sanitizedStackTrace = "at com.example.Foo.bar(Foo.kt:42)",
    )

    private fun createMockClient(respondBlock: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient {
        return HttpClient(MockEngine { request -> respondBlock(request) }) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                })
            }
        }
    }

    @Test
    fun successfulTicketCreation() = runBlocking {
        val mockClient = createMockClient { request ->
            assertEquals("api.notion.com", request.url.host)
            assertEquals("/v1/pages", request.url.encodedPath)
            assertTrue(request.headers["Authorization"]!!.startsWith("Bearer "))
            assertTrue(request.headers["Notion-Version"]!!.isNotEmpty())

            respond(
                content = """{
                    "id": "page-123",
                    "url": "https://notion.so/page-123",
                    "object": "page"
                }""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val backend = NotionTicketBackend(
            apiKey = "secret_test",
            bugTicketDbId = "db-456",
            httpClient = mockClient,
        )

        val result = backend.createTicket(testReport)
        assertTrue(result.success)
        assertEquals("page-123", result.ticketId)
        assertEquals("https://notion.so/page-123", result.url)
        assertEquals("Notion", result.backendName)
    }

    @Test
    fun httpErrorReturnsFailure() = runBlocking {
        val mockClient = createMockClient {
            respond(
                content = """{"message": "Invalid token"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val backend = NotionTicketBackend(
            apiKey = "bad_token",
            bugTicketDbId = "db-456",
            httpClient = mockClient,
        )

        val result = backend.createTicket(testReport)
        assertFalse(result.success)
        assertEquals("Notion", result.backendName)
    }

    @Test
    fun schemaMismatchRetriesWithActualTitlePropertyAndDropsMissingAggregationFields() = runBlocking {
        var postCount = 0
        var retryBody = ""

        val mockClient = createMockClient { request ->
            when (request.url.encodedPath) {
                "/v1/pages" -> {
                    postCount += 1
                    if (postCount == 1) {
                        respond(
                            content = """{
                                "object":"error",
                                "status":400,
                                "code":"validation_error",
                                "message":"Name is not a property that exists. Device Matrix is not a property that exists."
                            }""",
                            status = HttpStatusCode.BadRequest,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        retryBody = String(request.body.toByteArray())
                        respond(
                            content = """{
                                "id": "page-actual-title",
                                "url": "https://notion.so/page-actual-title",
                                "object": "page"
                            }""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
                "/v1/databases/db-456" -> respond(
                    content = """{
                        "id": "db-456",
                        "properties": {
                            "Task name": { "id": "title", "type": "title" },
                            "Severity": { "id": "severity", "type": "select" },
                            "Fingerprint": { "id": "fingerprint", "type": "rich_text" }
                        }
                    }""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected Notion path: ${request.url.encodedPath}")
            }
        }

        val backend = NotionTicketBackend(
            apiKey = "secret_test",
            bugTicketDbId = "db-456",
            httpClient = mockClient,
        )

        val result = backend.createTicket(testReport)

        assertTrue(result.success)
        assertEquals(2, postCount)
        assertTrue(retryBody.contains("Task name"))
        assertFalse(retryBody.contains("Device Matrix"))
        assertFalse(retryBody.contains("Occurrences"))
    }

    @Test
    fun defaultFieldNamesAreApplied() = runBlocking {
        var capturedBody = ""

        val mockClient = createMockClient { request ->
            capturedBody = String(request.body.toByteArray())
            respond(
                content = """{
                    "id": "page-789",
                    "url": "https://notion.so/page-789",
                    "object": "page"
                }""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val backend = NotionTicketBackend(
            apiKey = "secret_test",
            bugTicketDbId = "db-456",
            httpClient = mockClient,
        )

        val result = backend.createTicket(testReport)
        assertTrue(result.success)

        assertTrue(capturedBody.contains("Name"), "Should use default title field name")
        assertTrue(capturedBody.contains("Severity"), "Should use default severity field name")
    }
}
