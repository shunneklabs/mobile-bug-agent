package dev.sunnat629.mba.github

import dev.sunnat629.mba.github.model.GitHubFileResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.*
import kotlinx.serialization.json.Json

/**
 * Reads source files from a GitHub repository via the Contents API.
 *
 * @param token GitHub personal access token.
 * @param owner Repository owner.
 * @param repo Repository name.
 * @param ref Git ref (branch name, tag, or commit SHA). Defaults to main.
 */
public class GitHubSourceReader(
    private val token: String,
    private val owner: String,
    private val repo: String,
    private val ref: String = "main",
    private val httpClient: HttpClient = defaultHttpClient(),
) : AutoCloseable {

    private companion object {
        private const val BASE_URL = "https://api.github.com"

        private fun defaultHttpClient(): HttpClient = HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                })
            }
        }
    }

    /**
     * Read a file's contents.
     *
     * @param path File path in the repository (e.g., "src/main/kotlin/Foo.kt").
     * @param lineRange Optional line range to extract (1-indexed).
     * @return File contents as string, or null if file not found.
     */
    public suspend fun readFile(path: String, lineRange: IntRange? = null): String? {
        val response = getFileResponse(path) ?: return null
        val content = response.content ?: return null

        val decoded = content.replaceWhitespace().decodeBase64String()

        return if (lineRange != null) {
            val lines = decoded.lineSequence().toList()
            val start = maxOf(0, lineRange.first - 1)
            val endInclusive = minOf(lines.size - 1, lineRange.last - 1)
            if (start > endInclusive) null else lines.subList(start, endInclusive + 1).joinToString("\n")
        } else {
            decoded
        }
    }

    /**
     * Read a snippet around a specific line with context.
     *
     * @param path File path in the repository.
     * @param line Target line number (1-indexed).
     * @param context Number of lines before and after the target.
     * @return Snippet as string, or null if file not found.
     */
    public suspend fun readSnippetAroundLine(path: String, line: Int, context: Int = 20): String? {
        val response = getFileResponse(path) ?: return null
        val content = response.content ?: return null

        val decoded = content.replaceWhitespace().decodeBase64String()
        val lines = decoded.lineSequence().toList()

        val idx = line - 1
        if (idx < 0 || idx >= lines.size) return null

        val start = maxOf(0, idx - context)
        val end = minOf(lines.size - 1, idx + context)

        return lines.subList(start, end + 1).joinToString("\n")
    }

    override fun close() {
        httpClient.close()
    }

    private suspend fun getFileResponse(path: String): GitHubFileResponse? {
        return try {
            val response = httpClient.get("$BASE_URL/repos/$owner/$repo/contents/$path") {
                header("Authorization", "Bearer $token")
                header("Accept", "application/vnd.github.v3+json")
                parameter("ref", ref)
            }
            if (response.status.isSuccess()) response.body() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun String.replaceWhitespace(): String = replace("\n", "").replace("\r", "")
}
