package dev.sunnat629.mba.github

import dev.sunnat629.mba.github.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.*
import kotlinx.serialization.json.Json

/**
 * Result of a pull request operation.
 */
public sealed class PRResult {
    public data class Success(val prUrl: String, val prNumber: Int, val branch: String) : PRResult()
    public data class Failure(val reason: String) : PRResult()
}

/**
 * Creates pull requests with strict guardrails for AI-generated fixes.
 *
 * Guardrails:
 * - Hard refuse if base branch is main or master
 * - Hard refuse if diff is more than 20 lines
 * - Hard refuse if diff changes multiple files
 * - Hard refuse if diff adds new dependencies
 * - Hard refuse if diff changes public API surface
 * - Hard refuse if target file does not exist on base
 *
 * @param token GitHub personal access token.
 * @param owner Repository owner.
 * @param repo Repository name.
 */
public class GitHubPullRequestCreator(
    private val token: String,
    private val owner: String,
    private val repo: String,
    private val httpClient: HttpClient = defaultHttpClient(),
) : AutoCloseable {

    private companion object {
        private const val BASE_URL = "https://api.github.com"
        private const val MAX_DIFF_LINES = 20
        private val PROTECTED_BRANCHES = setOf("main", "master")

        private fun defaultHttpClient(): HttpClient = HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    explicitNulls = false
                })
            }
        }
    }

    /**
     * Open a fix PR with strict guardrails.
     *
     * @param branch Auto-generated branch name (or provide custom).
     * @param base Base branch (refused if main/master).
     * @param file Single file path to change.
     * @param oldContent Current file content (base64 decoded).
     * @param newContent New file content (base64 decoded).
     * @param title PR title.
     * @param body PR body.
     */
    public suspend fun openFix(
        branch: String? = null,
        base: String,
        file: String,
        oldContent: String,
        newContent: String,
        title: String,
        body: String,
    ): PRResult {
        // Guardrail: refuse protected branches
        if (base.lowercase() in PROTECTED_BRANCHES) {
            return PRResult.Failure("Refused: cannot create PR against protected branch '$base'")
        }

        // Guardrail: check target file exists on base
        val existingFile = getFileSha(file, base)
        if (existingFile == null) {
            return PRResult.Failure("Refused: target file '$file' does not exist on base branch '$base'")
        }

        // Guardrail: diff line count
        val oldLines = oldContent.lineSequence().toList()
        val newLines = newContent.lineSequence().toList()
        val diffLines = computeDiffLines(oldLines, newLines)
        if (diffLines > MAX_DIFF_LINES) {
            return PRResult.Failure("Refused: diff is $diffLines lines (max $MAX_DIFF_LINES)")
        }

        // Guardrail: no new dependencies
        if (addsNewDependencies(oldContent, newContent)) {
            return PRResult.Failure("Refused: fix adds new dependencies")
        }

        // Guardrail: no public API surface changes
        if (changesPublicApi(oldContent, newContent)) {
            return PRResult.Failure("Refused: fix changes public API surface")
        }

        // Generate branch name
        val branchName = branch ?: generateBranchName(file)

        // Create branch
        val baseSha = getBranchSha(base)
            ?: return PRResult.Failure("Failed to get SHA for base branch '$base'")

        if (!createBranch(branchName, baseSha)) {
            return PRResult.Failure("Failed to create branch '$branchName'")
        }

        // Commit file change
        val commitMessage = "fix: $title\n\nAI-generated fix. Human review required."
        if (!updateFile(branchName, file, newContent, existingFile.sha, commitMessage)) {
            return PRResult.Failure("Failed to update file '$file' on branch '$branchName'")
        }

        // Create PR
        val prBody = buildPrBody(body)
        val pr = createPullRequest(branchName, base, title, prBody)
            ?: return PRResult.Failure("Failed to create pull request")

        // Add labels
        addLabels(pr.number, listOf("mba/ai-generated", "do-not-merge-yet"))

        return PRResult.Success(pr.html_url, pr.number, branchName)
    }

    override fun close() {
        httpClient.close()
    }

    // ================================================================ //
    //  Guardrail checks
    // ================================================================ //

    private fun computeDiffLines(oldLines: List<String>, newLines: List<String>): Int {
        var changes = 0
        val maxLen = maxOf(oldLines.size, newLines.size)
        for (i in 0 until maxLen) {
            val old = oldLines.getOrElse(i) { "" }
            val new = newLines.getOrElse(i) { "" }
            if (old != new) changes++
        }
        return changes
    }

    private fun addsNewDependencies(oldContent: String, newContent: String): Boolean {
        val depPatterns = listOf(
            Regex("""implementation\(["'].*?["']\)"""),
            Regex("""api\(["'].*?["']\)"""),
            Regex("""<dependency>"""),
            Regex("""import .*\.R\.""") // Android R import changes
        )
        val oldDeps = depPatterns.flatMap { oldContent.findAll(it).map { m -> m.value } }.toSet()
        val newDeps = depPatterns.flatMap { newContent.findAll(it).map { m -> m.value } }.toSet()
        return (newDeps - oldDeps).isNotEmpty()
    }

    private fun changesPublicApi(oldContent: String, newContent: String): Boolean {
        val publicApiPatterns = listOf(
            Regex("""^\s*(public|protected)\s+""", RegexOption.MULTILINE),
            Regex("""^\s*public\s+""", RegexOption.MULTILINE),
            Regex("""^\s*fun\s+\w+""", RegexOption.MULTILINE),
            Regex("""^\s*(class|interface|object)\s+\w+""", RegexOption.MULTILINE),
        )
        val oldPublic = publicApiPatterns.sumOf { oldContent.findAll(it).count() }
        val newPublic = publicApiPatterns.sumOf { newContent.findAll(it).count() }
        return oldPublic != newPublic
    }

    // ================================================================ //
    //  GitHub API calls
    // ================================================================ //

    private suspend fun getFileSha(path: String, ref: String): GitHubFileResponse? {
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

    private suspend fun getBranchSha(branch: String): String? {
        return try {
            val response = httpClient.get("$BASE_URL/repos/$owner/$repo/git/ref/heads/$branch") {
                header("Authorization", "Bearer $token")
                header("Accept", "application/vnd.github.v3+json")
            }
            if (response.status.isSuccess()) {
                val refResponse: GitHubRefResponse = response.body()
                refResponse.obj.sha
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun createBranch(branchName: String, baseSha: String): Boolean {
        return try {
            val response = httpClient.post("$BASE_URL/repos/$owner/$repo/git/refs") {
                header("Authorization", "Bearer $token")
                header("Accept", "application/vnd.github.v3+json")
                contentType(ContentType.Application.Json)
                setBody(GitHubRefRequest("refs/heads/$branchName", baseSha))
            }
            response.status.isSuccess()
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun updateFile(
        branch: String,
        path: String,
        content: String,
        sha: String,
        message: String,
    ): Boolean {
        return try {
            val encoded = encodeBase64(content.encodeToByteArray())
            val response = httpClient.put("$BASE_URL/repos/$owner/$repo/contents/$path") {
                header("Authorization", "Bearer $token")
                header("Accept", "application/vnd.github.v3+json")
                contentType(ContentType.Application.Json)
                setBody(GitHubFileCreateRequest(message, encoded, sha))
            }
            response.status.isSuccess()
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun createPullRequest(
        head: String,
        base: String,
        title: String,
        body: String,
    ): GitHubPullRequestResponse? {
        return try {
            val response = httpClient.post("$BASE_URL/repos/$owner/$repo/pulls") {
                header("Authorization", "Bearer $token")
                header("Accept", "application/vnd.github.v3+json")
                contentType(ContentType.Application.Json)
                setBody(GitHubPullRequestRequest(title, body, head, base))
            }
            if (response.status.isSuccess()) response.body() else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun addLabels(issueNumber: Int, labels: List<String>) {
        try {
            httpClient.post("$BASE_URL/repos/$owner/$repo/issues/$issueNumber/labels") {
                header("Authorization", "Bearer $token")
                header("Accept", "application/vnd.github.v3+json")
                contentType(ContentType.Application.Json)
                setBody(GitHubLabelsRequest(labels))
            }
        } catch (_: Exception) {
            // Best effort — don't fail PR creation if labels fail
        }
    }

    private fun generateBranchName(file: String): String {
        val fingerprint = file.hashCode().toUInt().toString(16).take(8)
        val timestamp = System.currentTimeMillis() / 1000
        return "mba/auto-fix/$fingerprint-$timestamp"
    }

    private fun buildPrBody(userBody: String): String = buildString {
        appendLine("⚠️ **AI generated fix. Human review required. Do not merge without review.**")
        appendLine()
        appendLine(userBody)
        appendLine()
        appendLine("---")
        appendLine("*Auto-generated by MBA (Mobile Bug Agent)*")
    }
}
