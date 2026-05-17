package dev.sunnat629.mba.android

import dev.sunnat629.mba.core.model.DeviceContext
import dev.sunnat629.mba.core.model.RawCrashReport
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.core.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class ServerReportUploaderTest {
    @Test
    fun `posts raw crash to backend report endpoint with project and auth headers`() {
        runBlocking {
            val engine = MockEngine { request ->
                assertEquals("http://localhost:8080/report", request.url.toString())
                assertEquals("sample-app-debug", request.headers["X-MBA-Project-Key"])
                assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
                val body = request.body.readText()
                assertTrue(body.contains("\"id\":\"crash-1\""), body)
                assertTrue(body.contains("\"exceptionType\":\"java.lang.IllegalStateException\""), body)
                assertTrue(body.contains("\"device\":{"), body)
                assertTrue(!body.contains("\"report\":{"), body)
                respond(
                    content = """{"jobId":"job-123","status":"queued"}""",
                    status = HttpStatusCode.Accepted,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            val uploader = ServerReportUploader(
                endpoint = "http://localhost:8080/",
                projectKey = "sample-app-debug",
                serverApiKey = "test-token",
                httpClient = testClient(engine),
            )

            val result = uploader.upload(rawReport())

            assertEquals(BackendUploadResult.Accepted("job-123", "queued"), result)
            uploader.close()
        }
    }

    @Test
    fun `returns rejected when backend does not accept report`() {
        runBlocking {
            val uploader = ServerReportUploader(
                endpoint = "http://localhost:8080",
                projectKey = null,
                serverApiKey = null,
                httpClient = testClient(
                    MockEngine {
                        respond("bad", HttpStatusCode.BadRequest)
                    },
                ),
            )

            val result = uploader.upload(rawReport())

            assertEquals(BackendUploadResult.Rejected(400, "Bad Request"), result)
            uploader.close()
        }
    }

    private fun testClient(engine: MockEngine): HttpClient = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private suspend fun OutgoingContent.readText(): String = when (this) {
        is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
        is OutgoingContent.ReadChannelContent -> readFrom().readRemaining().readText()
        else -> toString()
    }

    private fun rawReport(): RawCrashReport = RawCrashReport(
        id = "crash-1",
        exceptionType = "java.lang.IllegalStateException",
        message = "boom",
        stackTrace = "java.lang.IllegalStateException: boom",
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
        autoFix = true,
        skipNotion = false,
    )
}