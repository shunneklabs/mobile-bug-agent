package dev.sunnat629.mba.agent.tools

import dev.sunnat629.mba.core.model.ProcessedCrashReport
import dev.sunnat629.mba.core.model.Severity
import dev.sunnat629.mba.core.model.TicketResult
import dev.sunnat629.mba.core.ticket.TicketBackend
import dev.sunnat629.mba.github.GitHubPullRequestCreator
import dev.sunnat629.mba.github.GitHubSourceReader
import dev.sunnat629.mba.github.PRResult

public data class ToolFailure(
    val code: String,
    val message: String,
    val recoverable: Boolean = true,
)

public data class ToolOutcome<out T>(
    val value: T? = null,
    val failure: ToolFailure? = null,
) {
    val success: Boolean get() = failure == null

    public companion object {
        public fun <T> success(value: T): ToolOutcome<T> = ToolOutcome(value = value)
        public fun <T> failure(code: String, message: String, recoverable: Boolean = true): ToolOutcome<T> =
            ToolOutcome(failure = ToolFailure(code, message, recoverable))
    }
}

public data class CreateTicketInput(val report: ProcessedCrashReport)

public data class CreateTicketOutput(
    val ticketId: String,
    val backendName: String,
    val url: String?,
)

public class CreateNotionTicketTool(private val backend: TicketBackend) {
    public suspend fun execute(input: CreateTicketInput): ToolOutcome<CreateTicketOutput> =
        createTicket(backend, input.report)
}

public class CreateGitHubIssueTool(private val backend: TicketBackend) {
    public suspend fun execute(input: CreateTicketInput): ToolOutcome<CreateTicketOutput> =
        createTicket(backend, input.report)
}

private suspend fun createTicket(
    backend: TicketBackend,
    report: ProcessedCrashReport,
): ToolOutcome<CreateTicketOutput> {
    val result: TicketResult = backend.createTicket(report)
    if (!result.success) {
        return ToolOutcome.failure(
            code = "ticket_failed",
            message = result.errorMessage ?: "${backend.name} did not create a ticket",
        )
    }

    return ToolOutcome.success(
        CreateTicketOutput(
            ticketId = result.ticketId,
            backendName = result.backendName,
            url = result.url,
        )
    )
}

public data class ReadSourceFileInput(
    val path: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
)

public data class ReadSourceFileOutput(
    val path: String,
    val content: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
)

public fun interface SourceFileReader {
    public suspend fun read(input: ReadSourceFileInput): String?
}

public class ReadSourceFileTool(private val reader: SourceFileReader) {
    public suspend fun execute(input: ReadSourceFileInput): ToolOutcome<ReadSourceFileOutput> {
        if (input.path.isBlank()) {
            return ToolOutcome.failure("invalid_source_path", "Source path is required")
        }
        if (input.startLine != null && input.endLine != null && input.startLine > input.endLine) {
            return ToolOutcome.failure("invalid_line_range", "startLine must be <= endLine")
        }

        val content = reader.read(input)
            ?: return ToolOutcome.failure("source_not_found", "Source file not found: ${input.path}")

        return ToolOutcome.success(
            ReadSourceFileOutput(
                path = input.path,
                content = content,
                startLine = input.startLine,
                endLine = input.endLine,
            )
        )
    }

    public companion object {
        public fun github(reader: GitHubSourceReader): ReadSourceFileTool = ReadSourceFileTool { input ->
            val range = when {
                input.startLine != null && input.endLine != null -> input.startLine..input.endLine
                else -> null
            }
            reader.readFile(input.path, range)
        }
    }
}

public data class SuggestFixInput(
    val report: ProcessedCrashReport,
    val source: ReadSourceFileOutput,
)

public data class FixProposal(
    val file: String,
    val oldContent: String,
    val newContent: String,
    val changedLines: Int,
    val deterministic: Boolean,
    val rationale: String,
)

public class SuggestFixTool {
    public fun execute(input: SuggestFixInput): ToolOutcome<FixProposal> {
        val report = input.report
        val source = input.source

        if (!report.exceptionTypeLooksLikeNpe()) {
            return ToolOutcome.failure("unsupported_crash", "Deterministic fix is only available for the demo NPE")
        }

        val oldContent = source.content
        val newContent = when {
            "val s: String? = null; s!!.length" in oldContent -> oldContent.replace(
                "val s: String? = null; s!!.length",
                "val s: String? = null\n                    checkNotNull(s) { \"Demo NPE value was unexpectedly null\" }.length",
            )
            "s!!.length" in oldContent -> oldContent.replace(
                "s!!.length",
                "checkNotNull(s) { \"Demo NPE value was unexpectedly null\" }.length",
            )
            else -> return ToolOutcome.failure(
                "no_deterministic_patch",
                "Could not find the known demo NPE pattern in ${source.path}",
            )
        }

        return ToolOutcome.success(
            FixProposal(
                file = source.path,
                oldContent = oldContent,
                newContent = newContent,
                changedLines = countChangedLines(oldContent, newContent),
                deterministic = true,
                rationale = "Replace an unsafe non-null assertion with checkNotNull so the demo crash has an explicit guarded failure.",
            )
        )
    }

    private fun ProcessedCrashReport.exceptionTypeLooksLikeNpe(): Boolean =
        raw.exceptionType.endsWith("NullPointerException") || title.contains("NullPointerException", ignoreCase = true)

    private fun countChangedLines(oldContent: String, newContent: String): Int {
        val oldLines = oldContent.lines()
        val newLines = newContent.lines()
        val max = maxOf(oldLines.size, newLines.size)
        return (0 until max).count { oldLines.getOrNull(it) != newLines.getOrNull(it) }
    }
}

public data class RunGuardrailsInput(
    val proposal: FixProposal,
    val baseBranch: String,
)

public data class GuardrailReport(
    val allowed: Boolean,
    val reasons: List<String>,
)

public class RunGuardrailsTool(
    private val protectedBranches: Set<String> = setOf("main", "master"),
    private val maxChangedLines: Int = 20,
) {
    public fun execute(input: RunGuardrailsInput): ToolOutcome<GuardrailReport> {
        val reasons = buildList {
            if (input.baseBranch.lowercase() in protectedBranches) add("base branch '${input.baseBranch}' is protected")
            if (input.proposal.changedLines > maxChangedLines) add("changed lines ${input.proposal.changedLines} exceeds $maxChangedLines")
            if (input.proposal.file.isBlank()) add("proposal file is required")
            if (addsNewDependency(input.proposal.oldContent, input.proposal.newContent)) add("new dependency detected")
            if (changesPublicApi(input.proposal.oldContent, input.proposal.newContent)) add("public API change detected")
        }

        return ToolOutcome.success(GuardrailReport(allowed = reasons.isEmpty(), reasons = reasons))
    }

    private fun addsNewDependency(oldContent: String, newContent: String): Boolean =
        newContent.lines().any { line ->
            val trimmed = line.trim()
            (trimmed.startsWith("implementation(") || trimmed.startsWith("api(") || trimmed.startsWith("compileOnly(")) &&
                trimmed !in oldContent.lines().map(String::trim)
        }

    private fun changesPublicApi(oldContent: String, newContent: String): Boolean =
        newContent.lines().any { line ->
            val trimmed = line.trimStart()
            (trimmed.startsWith("public fun ") || trimmed.startsWith("public class ") || trimmed.startsWith("public interface ")) &&
                trimmed !in oldContent.lines().map(String::trimStart)
        }
}

public data class OpenPullRequestInput(
    val proposal: FixProposal,
    val branch: String,
    val baseBranch: String,
    val title: String,
    val body: String,
)

public data class OpenPullRequestOutput(
    val prUrl: String,
    val prNumber: Int,
    val branch: String,
    val humanReviewRequired: Boolean = true,
)

public class OpenPullRequestTool(private val creator: GitHubPullRequestCreator) {
    public suspend fun execute(input: OpenPullRequestInput): ToolOutcome<OpenPullRequestOutput> {
        val guardedBody = input.body.withHumanReviewWarning()
        return when (val result = creator.openFix(
            branch = input.branch,
            base = input.baseBranch,
            file = input.proposal.file,
            oldContent = input.proposal.oldContent,
            newContent = input.proposal.newContent,
            title = input.title,
            body = guardedBody,
        )) {
            is PRResult.Success -> ToolOutcome.success(
                OpenPullRequestOutput(
                    prUrl = result.prUrl,
                    prNumber = result.prNumber,
                    branch = result.branch,
                )
            )
            is PRResult.Failure -> ToolOutcome.failure("pr_failed", result.reason)
        }
    }
}

public data class LinkPullRequestBackInput(
    val ticketUrl: String?,
    val issueUrl: String?,
    val prUrl: String,
)

public data class LinkPullRequestBackOutput(
    val links: Map<String, String>,
)

public class LinkPullRequestBackTool {
    public fun execute(input: LinkPullRequestBackInput): ToolOutcome<LinkPullRequestBackOutput> {
        val links = buildMap {
            input.ticketUrl?.takeIf(String::isNotBlank)?.let { put("notion_ticket", it) }
            input.issueUrl?.takeIf(String::isNotBlank)?.let { put("github_issue", it) }
            put("pull_request", input.prUrl)
        }
        return ToolOutcome.success(LinkPullRequestBackOutput(links))
    }
}

private fun String.withHumanReviewWarning(): String {
    val warning = "⚠️ Human review required before merge. This PR was opened by the MBA Koog auto-fix flow."
    return if (contains(warning)) this else "$this\n\n$warning"
}

public fun Severity.canAutoFixWhenEnabled(): Boolean = this == Severity.LOW