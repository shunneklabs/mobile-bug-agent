package dev.sunnat629.mba.server

internal data class GitHubRuntimeConfig(
    val token: String,
    val owner: String,
    val repo: String,
    val baseBranch: String,
) {
    val isConfigured: Boolean = token.isNotBlank() && owner.isNotBlank() && repo.isNotBlank()

    val configurationMessage: String =
        if (isConfigured) {
            "GitHub configured for $owner/$repo"
        } else {
            val missing = buildList {
                if (token.isBlank()) add("GITHUB_TOKEN")
                if (owner.isBlank()) add("GITHUB_OWNER or GITHUB_TARGET_OWNER")
                if (repo.isBlank()) add("GITHUB_REPO or GITHUB_TARGET_REPO")
            }.joinToString()

            "GitHub is not configured (missing $missing). Set GITHUB_TOKEN plus " +
                "GITHUB_OWNER/GITHUB_REPO, or GITHUB_TARGET_OWNER/GITHUB_TARGET_REPO. " +
                "GITHUB_REPO may also be owner/repo or https://github.com/owner/repo."
        }
}

internal object GitHubRuntimeConfigLoader {
    fun load(env: (String) -> String? = System::getenv): GitHubRuntimeConfig {
        val token = firstNonBlank(env("GITHUB_TOKEN"), env("GH_TOKEN"))
        val ownerFromEnv = firstNonBlank(env("GITHUB_OWNER"), env("GITHUB_TARGET_OWNER"))
        val repoFromEnv = firstNonBlank(env("GITHUB_REPO"), env("GITHUB_TARGET_REPO"), env("GITHUB_REPOSITORY"))
        val parsedRepo = parseRepoCoordinates(repoFromEnv)

        return GitHubRuntimeConfig(
            token = token,
            owner = ownerFromEnv.ifBlank { parsedRepo?.first.orEmpty() },
            repo = parsedRepo?.second ?: repoFromEnv,
            baseBranch = firstNonBlank(env("GITHUB_BASE_BRANCH"), "main"),
        )
    }

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

    private fun parseRepoCoordinates(value: String): Pair<String, String>? {
        val normalized = value
            .trim()
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removePrefix("git@github.com:")
            .removeSuffix(".git")
            .trim('/')

        val parts = normalized.split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null

        return parts[parts.size - 2] to parts.last()
    }
}