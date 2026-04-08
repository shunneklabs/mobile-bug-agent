package dev.sunnat629.mba.notion

import dev.sunnat629.mba.notion.model.NotionCreatePageRequest
import dev.sunnat629.mba.notion.model.NotionCreatePageResponse
import dev.sunnat629.mba.notion.model.NotionErrorResponse
import dev.sunnat629.mba.notion.model.NotionQueryDatabaseRequest
import dev.sunnat629.mba.notion.model.NotionQueryDatabaseResponse
import dev.sunnat629.mba.notion.model.NotionRetrievePageResponse
import dev.sunnat629.mba.notion.model.NotionUpdatePageRequest
import dev.sunnat629.mba.notion.model.NotionUpdatePageResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Minimal Notion HTTP client wrapper.
 */
class NotionClient(
    private val config: NotionConfig,
    private val http: HttpClient = defaultHttpClient(),
) {

    suspend fun createPage(request: NotionCreatePageRequest): NotionCreatePageResponse {
        val resp = http.post("${config.baseUrl}/pages") {
            header("Authorization", "Bearer ${config.token}")
            header("Notion-Version", config.notionVersion)
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(request)
        }

        if (resp.status.value >= 400) {
            val err = runCatching { resp.body<NotionErrorResponse>() }.getOrNull()
            error("Notion createPage failed: HTTP ${resp.status.value} ${err?.code ?: ""} ${err?.message ?: ""}".trim())
        }

        return resp.body()
    }

    suspend fun retrievePage(pageId: String): NotionRetrievePageResponse {
        val resp = http.get("${config.baseUrl}/pages/$pageId") {
            header("Authorization", "Bearer ${config.token}")
            header("Notion-Version", config.notionVersion)
            accept(ContentType.Application.Json)
        }

        if (resp.status.value >= 400) {
            val err = runCatching { resp.body<NotionErrorResponse>() }.getOrNull()
            error("Notion retrievePage failed: HTTP ${resp.status.value} ${err?.code ?: ""} ${err?.message ?: ""}".trim())
        }

        return resp.body()
    }

    suspend fun updatePage(pageId: String, request: NotionUpdatePageRequest): NotionUpdatePageResponse {
        val resp = http.patch("${config.baseUrl}/pages/$pageId") {
            header("Authorization", "Bearer ${config.token}")
            header("Notion-Version", config.notionVersion)
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(request)
        }

        if (resp.status.value >= 400) {
            val err = runCatching { resp.body<NotionErrorResponse>() }.getOrNull()
            error("Notion updatePage failed: HTTP ${resp.status.value} ${err?.code ?: ""} ${err?.message ?: ""}".trim())
        }

        return resp.body()
    }

    suspend fun queryDatabase(databaseId: String, request: NotionQueryDatabaseRequest): NotionQueryDatabaseResponse {
        val resp = http.post("${config.baseUrl}/databases/$databaseId/query") {
            header("Authorization", "Bearer ${config.token}")
            header("Notion-Version", config.notionVersion)
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(request)
        }

        if (resp.status.value >= 400) {
            val err = runCatching { resp.body<NotionErrorResponse>() }.getOrNull()
            error("Notion queryDatabase failed: HTTP ${resp.status.value} ${err?.code ?: ""} ${err?.message ?: ""}".trim())
        }

        return resp.body()
    }

    companion object {
        fun defaultHttpClient(): HttpClient = HttpClient {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                        explicitNulls = false
                    }
                )
            }
        }
    }
}
