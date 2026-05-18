package dev.sunnat629.mba.android

import dev.sunnat629.mba.agent.runtime.RawCrashUploadResult
import dev.sunnat629.mba.agent.runtime.RawCrashUploader
import dev.sunnat629.mba.core.model.RawCrashReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class ServerReportUploader(
    private val endpoint: String,
    private val projectKey: String?,
    private val serverApiKey: String?,
    private val httpClient: HttpClient = defaultClient(),
) : RawCrashUploader {
    override suspend fun upload(rawReport: RawCrashReport): RawCrashUploadResult {
        val payload = Json.encodeToString(rawReport)
        val response: HttpResponse = httpClient.post("${endpoint.trimEnd('/')}/report") {
            contentType(ContentType.Application.Json)
            projectKey?.takeIf { it.isNotBlank() }?.let { header("X-MBA-Project-Key", it) }
            serverApiKey?.takeIf { it.isNotBlank() }?.let { header("X-MBA-API-Key", it) }
            setBody(payload)
        }

        return if (response.status == HttpStatusCode.Accepted) {
            val accepted = response.body<BackendAcceptedResponse>()
            RawCrashUploadResult.Accepted(accepted.jobId, accepted.status)
        } else {
            RawCrashUploadResult.Rejected(response.status.value, response.status.description)
        }
    }

    fun close() {
        httpClient.close()
    }

    private companion object {
        fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }
}

@Serializable
private data class BackendAcceptedResponse(
    val jobId: String,
    val status: String,
)
