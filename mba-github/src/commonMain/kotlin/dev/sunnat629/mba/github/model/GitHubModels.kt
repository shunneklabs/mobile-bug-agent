package dev.sunnat629.mba.github.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for creating a GitHub Issue.
 */
@Serializable
data class GitHubIssueRequest(
    val title: String,
    val body: String,
    val labels: List<String> = emptyList(),
)

/**
 * Response from GitHub Issue creation.
 */
@Serializable
data class GitHubIssueResponse(
    val id: Long,
    val number: Int,
    val html_url: String,
    val title: String,
    val state: String,
    val body: String? = null,
)

/**
 * Request body for creating a pull request.
 */
@Serializable
data class GitHubPullRequestRequest(
    val title: String,
    val body: String,
    val head: String,
    val base: String,
)

/**
 * Response from GitHub PR creation.
 */
@Serializable
data class GitHubPullRequestResponse(
    val id: Long,
    val number: Int,
    val html_url: String,
    val title: String,
    val state: String,
    val merged: Boolean? = null,
)

/**
 * Request body for creating a branch (via refs).
 */
@Serializable
data class GitHubRefRequest(
    val ref: String,
    val sha: String,
)

/**
 * Response from GitHub ref creation.
 */
@Serializable
data class GitHubRefResponse(
    val ref: String,
    val url: String,
    @SerialName("object")
    val obj: GitHubRefObject,
)

@Serializable
data class GitHubRefObject(
    val sha: String,
    val type: String,
    val url: String,
)

/**
 * Request body for creating a file (for auto-fix commits).
 */
@Serializable
data class GitHubFileCreateRequest(
    val message: String,
    val content: String, // base64 encoded
    val sha: String? = null, // required if updating existing file
    val branch: String? = null,
)

/**
 * Response from GitHub file creation/update.
 */
@Serializable
data class GitHubFileCreateResponse(
    val content: GitHubFileContent? = null,
    val commit: GitHubCommit,
)

@Serializable
data class GitHubFileContent(
    val name: String,
    val path: String,
    val sha: String,
    val size: Int,
    val url: String,
)

@Serializable
data class GitHubCommit(
    val sha: String,
    val url: String,
    val html_url: String,
)

/**
 * Response from GitHub file get.
 */
@Serializable
data class GitHubFileResponse(
    val name: String,
    val path: String,
    val sha: String,
    val size: Int,
    val content: String? = null, // base64 encoded
    val download_url: String? = null,
)

/**
 * Response from GitHub API error.
 */
@Serializable
data class GitHubErrorResponse(
    val message: String,
    val documentation_url: String? = null,
    val errors: List<GitHubErrorDetail>? = null,
)

@Serializable
data class GitHubErrorDetail(
    val resource: String,
    val field: String,
    val code: String,
)

/**
 * Response for listing commits (for git blame simulation).
 */
@Serializable
data class GitHubCommitResponse(
    val sha: String,
    val commit: GitHubCommitDetail,
    val author: GitHubAuthor? = null,
)

@Serializable
data class GitHubCommitDetail(
    val author: GitHubAuthor,
    val message: String,
)

@Serializable
data class GitHubAuthor(
    val login: String? = null,
    val name: String? = null,
    val email: String? = null,
)

/**
 * Request body for adding labels to an issue.
 */
@Serializable
data class GitHubLabelsRequest(
    val labels: List<String>,
)

/**
 * Request body for assigning reviewers to a pull request.
 */
@Serializable
data class GitHubReviewersRequest(
    val reviewers: List<String>,
)
