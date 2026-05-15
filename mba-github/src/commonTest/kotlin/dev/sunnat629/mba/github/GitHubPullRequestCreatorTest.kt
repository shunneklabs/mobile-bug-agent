package dev.sunnat629.mba.github

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitHubPullRequestCreatorTest {

    @Test
    fun openFixAssignsReviewerFromFileHistory() = runTest {
        val requestPaths = mutableListOf<String>()

        val mockEngine = MockEngine { request ->
            requestPaths += request.url.encodedPath
            when {
                request.url.encodedPath.contains("/contents/src/Main.kt") && request.method == HttpMethod.Get -> respondJson(
                    """{"name":"Main.kt","path":"src/Main.kt","sha":"file-sha","size":100}"""
                )

                request.url.encodedPath.contains("/git/ref/heads/develop") -> respondJson(
                    """{"ref":"refs/heads/develop","url":"x","object":{"sha":"base-sha","type":"commit","url":"x"}}"""
                )

                request.url.encodedPath.endsWith("/git/refs") && request.method == HttpMethod.Post -> respondJson(
                    """{"ref":"refs/heads/mba/auto-fix/test","url":"x","object":{"sha":"base-sha","type":"commit","url":"x"}}"""
                )

                request.url.encodedPath.contains("/contents/src/Main.kt") && request.method == HttpMethod.Put -> {
                    val body = request.body.toByteArray().decodeToString()
                    assertTrue(body.contains("\"branch\""))
                    respondJson("""{"content":null,"commit":{"sha":"new-sha","url":"x","html_url":"x"}}""")
                }

                request.url.encodedPath.endsWith("/pulls") && request.method == HttpMethod.Post -> respondJson(
                    """{"id":1,"number":42,"html_url":"https://github.com/o/r/pull/42","title":"fix","state":"open"}"""
                )

                request.url.encodedPath.endsWith("/issues/42/labels") -> respondJson("""{"ok":true}""")

                request.url.encodedPath.endsWith("/commits") -> respondJson(
                    """[
                        {
                          "sha":"commit-sha",
                          "commit":{"author":{"login":null,"name":"Jane","email":"jane@example.com"},"message":"msg"},
                          "author":{"login":"jane-dev","name":"Jane","email":"jane@example.com"}
                        }
                    ]"""
                )

                request.url.encodedPath.endsWith("/pulls/42/requested_reviewers") -> {
                    val body = request.body.toByteArray().decodeToString()
                    assertTrue(body.contains("jane-dev"))
                    respondJson("""{"ok":true}""")
                }

                else -> respond("unexpected: ${request.url}", HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val creator = GitHubPullRequestCreator(
            token = "token",
            owner = "owner",
            repo = "repo",
            httpClient = client,
        )

        val result = creator.openFix(
            branch = "mba/auto-fix/test",
            base = "develop",
            file = "src/Main.kt",
            oldContent = "fun a() = 1",
            newContent = "fun a() = 2",
            title = "Fix crash",
            body = "Details",
        )

        assertIs<PRResult.Success>(result)
        assertEquals(42, result.prNumber)
        assertTrue(requestPaths.any { it.endsWith("/pulls/42/requested_reviewers") })

        creator.close()
        client.close()
    }

    @Test
    fun openFixDoesNotFailWhenReviewerAssignmentFails() = runTest {
        val mockEngine = MockEngine { request ->
            when {
                request.url.encodedPath.contains("/contents/src/Main.kt") && request.method == HttpMethod.Get -> respondJson(
                    """{"name":"Main.kt","path":"src/Main.kt","sha":"file-sha","size":100}"""
                )

                request.url.encodedPath.contains("/git/ref/heads/develop") -> respondJson(
                    """{"ref":"refs/heads/develop","url":"x","object":{"sha":"base-sha","type":"commit","url":"x"}}"""
                )

                request.url.encodedPath.endsWith("/git/refs") && request.method == HttpMethod.Post -> respondJson(
                    """{"ref":"refs/heads/mba/auto-fix/test","url":"x","object":{"sha":"base-sha","type":"commit","url":"x"}}"""
                )

                request.url.encodedPath.contains("/contents/src/Main.kt") && request.method == HttpMethod.Put -> respondJson(
                    """{"content":null,"commit":{"sha":"new-sha","url":"x","html_url":"x"}}"""
                )

                request.url.encodedPath.endsWith("/pulls") && request.method == HttpMethod.Post -> respondJson(
                    """{"id":1,"number":7,"html_url":"https://github.com/o/r/pull/7","title":"fix","state":"open"}"""
                )

                request.url.encodedPath.endsWith("/issues/7/labels") -> respondJson("""{"ok":true}""")

                request.url.encodedPath.endsWith("/commits") -> respondJson(
                    """[
                        {
                          "sha":"commit-sha",
                          "commit":{"author":{"login":null,"name":"Alice","email":"alice@example.com"},"message":"msg"},
                          "author":{"login":"alice-dev","name":"Alice","email":"alice@example.com"}
                        }
                    ]"""
                )

                request.url.encodedPath.endsWith("/pulls/7/requested_reviewers") -> respond(
                    "{" + "\"message\":\"Validation Failed\"}",
                    HttpStatusCode.UnprocessableEntity,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )

                else -> respond("unexpected: ${request.url}", HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val creator = GitHubPullRequestCreator(
            token = "token",
            owner = "owner",
            repo = "repo",
            httpClient = client,
        )

        val result = creator.openFix(
            branch = "mba/auto-fix/test",
            base = "develop",
            file = "src/Main.kt",
            oldContent = "fun a() = 1",
            newContent = "fun a() = 2",
            title = "Fix crash",
            body = "Details",
        )

        assertIs<PRResult.Success>(result)

        creator.close()
        client.close()
    }

    private fun MockRequestHandleScope.respondJson(content: String) = respond(
        content = ByteReadChannel(content),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}