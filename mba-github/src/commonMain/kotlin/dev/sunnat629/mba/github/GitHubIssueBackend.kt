package dev.sunnat629.mba.github

import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.TicketResult
import dev.sunnat629.mba.core.ticket.TicketBackend
import dev.sunnat629.mba.core.ticket.TicketUpdate
import dev.sunnat629.mba.github.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.Json

/**
 * GitHub Issues implementation of [TicketBackend].
 *
 * Maps [ProcessedCrashReport] to a GitHub Issue with Markdown body,
 * severity labels, and auto-generated tags.
 *
 * @param token GitHub personal access token.
 * @param owner Repository owner (user or org).
 * @param repo Repository name.
 */
public class GitHubIssueBackend(
    private val token: String,
    private val owner: String,
    private val repo: String,
    private val httpClient: HttpClient = defaultHttpClient(),
) : TicketBackend, AutoCloseable {

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

    override val name: String = "GitHub"

    override suspend fun createTicket(report: ProcessedCrashReport): TicketResult {
        return try {
            findOpenIssueByFingerprint(report.fingerprint)?.let { existing ->
                MBALog.i("GitHub", "Open issue already exists for fingerprint=${report.fingerprint.take(12)}: #${existing.number}")
                return TicketResult(
                    ticketId = existing.number.toString(),
                    backendName = name,
                    url = existing.html_url,
                    success = true,
                )
            }

            val labels = buildSeverityLabels(report)
            logPayload("create", report)

            val issueRequest = GitHubIssueRequest(
                title = report.title,
                body = buildIssueBody(report),
                labels = labels,
            )

            val httpResponse = httpClient.post("$BASE_URL/repos/$owner/$repo/issues") {
                header("Authorization", "Bearer $token")
                header("Accept", "application/vnd.github.v3+json")
                contentType(ContentType.Application.Json)
                setBody(issueRequest)
            }

            if (!httpResponse.status.isSuccess()) {
                val errorBody = httpResponse.bodyAsText()
                return TicketResult.failure(name, "GitHub API ${httpResponse.status}: $errorBody")
            }

            val response: GitHubIssueResponse = httpResponse.body()
            TicketResult(
                ticketId = response.number.toString(),
                backendName = name,
                url = response.html_url,
                success = true,
            )
        } catch (e: Exception) {
            TicketResult.failure(name, e.message ?: "Unknown error")
        }
    }

    override suspend fun updateTicket(ticketId: String, update: TicketUpdate): TicketResult {
        return try {
            val issueNumber = ticketId.toIntOrNull()
                ?: return TicketResult.failure(name, "Invalid ticket id: $ticketId (expected GitHub issue number)")

            var title: String? = null
            var issueBody: String? = null
            var labels: List<String>? = null
            update.report?.let { report ->
                logPayload("update", report)
                title = report.title
                issueBody = buildIssueBody(report, update)
                labels = buildSeverityLabels(report)
            }
            val addDevice = update.addDevice
            if (addDevice != null && issueBody == null) {
                val existingIssue = getIssue(issueNumber)
                issueBody = buildString {
                    append(existingIssue?.body ?: "")
                    appendLine()
                    appendLine()
                    append("---")
                    appendLine()
                    append("**Additional device:** ${addDevice.displayName}")
                }
            }
            val requestBody = buildJsonObject {
                title?.let { put("title", it) }
                issueBody?.let { put("body", it) }
                labels?.let { values ->
                    putJsonArray("labels") {
                        values.forEach { label -> add(label) }
                    }
                }
            }

            val httpResponse = httpClient.patch("$BASE_URL/repos/$owner/$repo/issues/$issueNumber") {
                header("Authorization", "Bearer $token")
                header("Accept", "application/vnd.github.v3+json")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }

            if (!httpResponse.status.isSuccess()) {
                return TicketResult.failure(name, "GitHub API ${httpResponse.status}: ${httpResponse.bodyAsText()}")
            }

            TicketResult(ticketId = ticketId, backendName = name, success = true)
        } catch (e: Exception) {
            TicketResult.failure(name, e.message ?: "Unknown error")
        }
    }

    override fun close() {
        httpClient.close()
    }

    // ================================================================ //
    //  Internal helpers
    // ================================================================ //

    private fun buildSeverityLabels(report: ProcessedCrashReport): List<String> {
        val severityLabel = when (report.severity.name.lowercase()) {
            "critical" -> "mba/critical"
            "high" -> "mba/high"
            "medium" -> "mba/medium"
            "low" -> "mba/low"
            else -> "mba/unknown"
        }
        return listOf(severityLabel, "mba/auto-generated")
    }

    private fun logPayload(action: String, report: ProcessedCrashReport) {
        MBALog.i(
            "GitHub",
            "GitHub $action payload: confidence=${report.confidence}, " +
                "steps=${report.stepsToReproduce?.isNotBlank() == true}, " +
                "cause=${report.possibleCause?.isNotBlank() == true}, " +
                "title='${report.title}'",
        )
    }

    private fun buildIssueBody(report: ProcessedCrashReport, update: TicketUpdate? = null): String = buildString {
        val isRawFallback = report.isRawFallback()
        appendLine("## Crash Report")
        appendLine()
        appendLine("**Title:** ${report.title}")
        if (!isRawFallback) {
            appendLine("**Severity:** ${report.severity} (confidence: ${"%.0f".format(report.confidence * 100)}%)")
        }
        appendLine("**Fingerprint:** `${report.fingerprint}`")
        appendLine("**Occurred At:** ${report.raw.timestamp}")
        update?.occurrenceCount?.let { appendLine("**Occurrences:** $it") }
        update?.uniqueDeviceCount?.let { appendLine("**Unique Devices:** $it") }
        update?.newOccurrenceTime?.let { appendLine("**Last Seen:** $it") }
        appendLine()

        appendLine("### Description")
        appendLine(report.rawSummary())
        appendLine()

        if (!isRawFallback) report.crashFile?.let { file ->
            appendLine("### Location")
            append("- **File:** `$file`")
            report.crashLine?.let { line ->
                append(" **Line:** $line")
            }
            report.crashMethod?.let { method ->
                append(" **Method:** `$method`")
            }
            appendLine()
            appendLine()
        }

        if (!isRawFallback) {
            report.possibleCause?.let { cause ->
                appendLine("### Possible Cause")
                appendLine(cause)
                appendLine()
            }

            report.stepsToReproduce?.let { steps ->
                appendLine("### Steps to Reproduce")
                appendLine(steps)
                appendLine()
            }
        }

        appendLine("### Device Context")
        appendLine("- **Device Model:** ${report.raw.device.displayName}")
        appendLine("- **OS Version:** Android ${report.raw.device.osVersion} (API ${report.raw.device.sdkInt})")
        appendLine("- **Locale:** ${report.raw.device.locale}")
        appendLine("- **Memory:** ${report.raw.device.availableMemoryMb}MB / ${report.raw.device.totalMemoryMb}MB")
        appendLine("- **App Version:** ${report.raw.appVersion}")
        appendLine("- **Build Type:** ${report.raw.buildType}")
        report.raw.currentScreen?.let { appendLine("- **Current Screen:** $it") }
        appendLine()

        if (report.raw.breadcrumbs.isNotEmpty()) {
            appendLine("### Breadcrumbs")
            report.raw.breadcrumbs.forEach { breadcrumb ->
                appendLine("- $breadcrumb")
            }
            appendLine()
        }

        if (report.sanitizedStackTrace.isNotBlank()) {
            appendLine("### Stack Trace")
            appendLine("```")
            appendLine(report.sanitizedStackTrace.take(3000))
            appendLine("```")
        }

        appendLine()
        appendLine("---")
        appendLine("*Auto-generated by MBA (Mobile Bug Agent)*")
    }

    private fun ProcessedCrashReport.isRawFallback(): Boolean = confidence <= 0.0f

    private fun ProcessedCrashReport.rawSummary(): String =
        raw.message
            ?.takeIf { it.isNotBlank() }
            ?: sanitizedStackTrace.lineSequence().firstOrNull()
                ?.takeIf { it.isNotBlank() }
            ?: raw.exceptionType

    private suspend fun getIssue(number: Int): GitHubIssueResponse? {
        return try {
            val response = httpClient.get("$BASE_URL/repos/$owner/$repo/issues/$number") {
                header("Authorization", "Bearer $token")
                header("Accept", "application/vnd.github.v3+json")
            }
            if (response.status.isSuccess()) response.body() else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun findOpenIssueByFingerprint(fingerprint: String): GitHubIssueResponse? {
        return try {
            val response = httpClient.get("$BASE_URL/repos/$owner/$repo/issues") {
                header("Authorization", "Bearer $token")
                header("Accept", "application/vnd.github.v3+json")
                parameter("state", "open")
                parameter("labels", "mba/auto-generated")
                parameter("per_page", "100")
            }
            if (!response.status.isSuccess()) return null
            response.body<List<GitHubIssueResponse>>().firstOrNull { issue ->
                issue.body?.contains("**Fingerprint:** `$fingerprint`") == true ||
                    issue.body?.contains(fingerprint) == true
            }
        } catch (_: Exception) {
            null
        }
    }
}
