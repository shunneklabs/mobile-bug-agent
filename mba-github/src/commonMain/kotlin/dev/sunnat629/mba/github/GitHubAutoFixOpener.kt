package dev.sunnat629.mba.github

import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.github.model.GitHubIssueRequest
import dev.sunnat629.mba.github.model.GitHubIssueResponse
import dev.sunnat629.mba.github.model.GitHubRefRequest
import dev.sunnat629.mba.github.model.GitHubRefResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Result of the GitHub auto-fix flow (v1).
 *
 * v1 = create Issue + create a tracking branch off the configured base.
 * The actual patch / draft PR is produced by [GitHubPullRequestCreator] in a
 * later step once the LLM patch loop is wired into the server.
 */
public sealed class AutoFixResult {
    public data class Success(
        val issueNumber: Int,
        val issueUrl: String,
        val branch: String,
    ) : AutoFixResult()

    public data class IssueOnly(
        val issueNumber: Int,
        val issueUrl: String,
        val branchError: String,
    ) : AutoFixResult()

    public data class Failure(val reason: String) : AutoFixResult()
}

/**
 * High-level v1 helper for the GitHub auto-fix path.
 *
 * Flow:
 *  1. `POST /repos/{owner}/{repo}/issues` — create a GitHub Issue from the crash.
 *  2. `GET /repos/{owner}/{repo}/git/ref/heads/{baseBranch}` — read base SHA.
 *  3. `POST /repos/{owner}/{repo}/git/refs` — create `autofix/issue-N-<slug>` from base.
 *
 * Steps 4+ (read target file, agent patch, commit, draft PR) live in
 * [GitHubPullRequestCreator] and are intentionally *not* called here — the
 * server emits a "branch ready, awaiting agent patch" progress event instead.
 *
 * Every step swallows exceptions and returns a structured [AutoFixResult] so
 * callers never have to catch.
 *
 * @param token GitHub PAT or App token with `repo` scope.
 * @param owner Repository owner (user or org).
 * @param repo Repository name.
 * @param baseBranch Default base branch (e.g., "main").
 */
public class GitHubAutoFixOpener(
    private val token: String,
    private val owner: String,
    private val repo: String,
    private val baseBranch: String = "main",
    private val httpClient: HttpClient = defaultHttpClient(),
) : AutoCloseable {

    private companion object {
        private const val BASE_URL = "https://api.github.com"

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

    public suspend fun openAutoFix(report: ProcessedCrashReport): AutoFixResult {
        // 1. Create Issue.
        val issue = createIssue(report) ?: return AutoFixResult.Failure(
            "Failed to create GitHub issue (check GITHUB_TOKEN/OWNER/REPO and repo permissions)"
        )

        // 2. Read base SHA.
        val baseSha = getBranchSha(baseBranch)
            ?: return AutoFixResult.IssueOnly(
                issue.number,
                issue.html_url,
                "Could not resolve SHA for base branch '$baseBranch'",
            )

        // 3. Create branch.
        val branchName = "autofix/issue-${issue.number}-${slug(report.title)}"
        val branchOk = createBranch(branchName, baseSha)
        return if (branchOk) {
            AutoFixResult.Success(issue.number, issue.html_url, branchName)
        } else {
            AutoFixResult.IssueOnly(
                issue.number,
                issue.html_url,
                "Branch '$branchName' could not be created (already exists or insufficient scope?)",
            )
        }
    }

    override fun close() {
        httpClient.close()
    }

    // ================================================================ //
    //  HTTP helpers
    // ================================================================ //

    private suspend fun createIssue(report: ProcessedCrashReport): GitHubIssueResponse? = try {
        val request = GitHubIssueRequest(
            title = "[MBA] ${report.title}",
            body = buildIssueBody(report),
            labels = listOf("mba/auto-generated", "mba/${report.severity.name.lowercase()}"),
        )
        val response = httpClient.post("$BASE_URL/repos/$owner/$repo/issues") {
            header("Authorization", "Bearer $token")
            header("Accept", "application/vnd.github.v3+json")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status.isSuccess()) response.body<GitHubIssueResponse>() else null
    } catch (_: Exception) {
        null
    }

    private suspend fun getBranchSha(branch: String): String? = try {
        val response = httpClient.get("$BASE_URL/repos/$owner/$repo/git/ref/heads/$branch") {
            header("Authorization", "Bearer $token")
            header("Accept", "application/vnd.github.v3+json")
        }
        if (response.status.isSuccess()) response.body<GitHubRefResponse>().obj.sha else null
    } catch (_: Exception) {
        null
    }

    private suspend fun createBranch(branchName: String, baseSha: String): Boolean = try {
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

    private fun buildIssueBody(report: ProcessedCrashReport): String = buildString {
        appendLine("## Auto-detected crash")
        appendLine()
        appendLine("**Severity:** ${report.severity} (confidence: ${"%.0f".format(report.confidence * 100)}%)")
        appendLine("**Fingerprint:** `${report.fingerprint}`")
        appendLine()
        appendLine("### Description")
        appendLine(report.description)
        appendLine()
        report.crashFile?.let { file ->
            appendLine("### Location")
            append("- **File:** `$file`")
            report.crashLine?.let { append(" **Line:** $it") }
            report.crashMethod?.let { append(" **Method:** `$it`") }
            appendLine(); appendLine()
        }
        report.possibleCause?.let {
            appendLine("### Possible cause")
            appendLine(it); appendLine()
        }
        report.stepsToReproduce?.let {
            appendLine("### Steps to reproduce")
            appendLine(it); appendLine()
        }
        appendLine("### Device")
        appendLine("- ${report.raw.device.displayName} (Android ${report.raw.device.osVersion} / API ${report.raw.device.sdkInt})")
        appendLine("- App ${report.raw.appVersion} (${report.raw.buildType})")
        appendLine()
        if (report.sanitizedStackTrace.isNotBlank()) {
            appendLine("### Stack trace")
            appendLine("```")
            appendLine(report.sanitizedStackTrace.take(3000))
            appendLine("```")
        }
        appendLine()
        appendLine("---")
        appendLine("_Auto-generated by MBA. A tracking branch `autofix/issue-N-<slug>` has been created — the agent will push a patch + draft PR in a follow-up step._")
    }

    private fun slug(title: String): String =
        title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .ifEmpty { "crash" }
}
