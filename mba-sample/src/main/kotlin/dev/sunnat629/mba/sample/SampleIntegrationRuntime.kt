package dev.sunnat629.mba.sample

import android.content.Context
import dev.sunnat629.mba.android.MBAAndroid
import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.github.GitHubIssueBackend
import dev.sunnat629.mba.notion.NotionTicketBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SampleIntegrationRuntime {
    private const val TAG = "SampleIntegration"
    private const val PREFS_NAME = "mba_sample_integrations"
    private const val KEY_MODE = "integration_mode"

    private val _mode = MutableStateFlow(SampleIntegrationMode.CALLBACK_ONLY)
    val mode: StateFlow<SampleIntegrationMode> = _mode.asStateFlow()

    val hasNotionConfig: Boolean
        get() = BuildConfig.NOTION_API_KEY.isNotBlank() && BuildConfig.NOTION_TICKET_DB_ID.isNotBlank()

    val hasGitHubConfig: Boolean
        get() = BuildConfig.GITHUB_TOKEN.isNotBlank() &&
            BuildConfig.GITHUB_OWNER.isNotBlank() &&
            BuildConfig.GITHUB_REPO.isNotBlank()

    private val notionBackend: NotionTicketBackend? by lazy {
        if (hasNotionConfig) {
            NotionTicketBackend(
                apiKey = BuildConfig.NOTION_API_KEY,
                bugTicketDbId = BuildConfig.NOTION_TICKET_DB_ID,
            )
        } else {
            null
        }
    }

    private val githubBackend: GitHubIssueBackend? by lazy {
        if (hasGitHubConfig) {
            GitHubIssueBackend(
                token = BuildConfig.GITHUB_TOKEN,
                owner = BuildConfig.GITHUB_OWNER,
                repo = BuildConfig.GITHUB_REPO,
            )
        } else {
            null
        }
    }

    fun restore(context: Context): SampleIntegrationMode {
        val bestAvailable = bestAvailableMode()
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE, null)
            ?.let { value -> SampleIntegrationMode.entries.firstOrNull { it.name == value } }
        val requested = saved
            ?.takeUnless { it == SampleIntegrationMode.CALLBACK_ONLY && bestAvailable != SampleIntegrationMode.CALLBACK_ONLY }
            ?: bestAvailable
        return apply(requested)
    }

    fun select(context: Context, mode: SampleIntegrationMode): SampleIntegrationMode {
        val applied = apply(mode)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, applied.name)
            .apply()
        return applied
    }

    private fun apply(mode: SampleIntegrationMode): SampleIntegrationMode {
        val applied = when (mode) {
            SampleIntegrationMode.CALLBACK_ONLY -> mode
            SampleIntegrationMode.NOTION -> if (hasNotionConfig) mode else SampleIntegrationMode.CALLBACK_ONLY
            SampleIntegrationMode.GITHUB -> if (hasGitHubConfig) mode else SampleIntegrationMode.CALLBACK_ONLY
            SampleIntegrationMode.BOTH -> when {
                hasNotionConfig && hasGitHubConfig -> mode
                hasNotionConfig -> SampleIntegrationMode.NOTION
                hasGitHubConfig -> SampleIntegrationMode.GITHUB
                else -> SampleIntegrationMode.CALLBACK_ONLY
            }
        }

        MBAAndroid.setTicketBackends(
            notionBackend = if (applied.usesNotion) notionBackend else null,
            githubBackend = if (applied.usesGitHub) githubBackend else null,
        )
        MBALog.i(
            TAG,
            "Ticket sinks: mode=${applied.name}, notion=${applied.usesNotion}, github=${applied.usesGitHub}",
        )
        _mode.value = applied
        return applied
    }

    private fun bestAvailableMode(): SampleIntegrationMode = when {
        hasNotionConfig && hasGitHubConfig -> SampleIntegrationMode.BOTH
        hasNotionConfig -> SampleIntegrationMode.NOTION
        hasGitHubConfig -> SampleIntegrationMode.GITHUB
        else -> SampleIntegrationMode.CALLBACK_ONLY
    }
}

enum class SampleIntegrationMode(
    val label: String,
    val usesNotion: Boolean,
    val usesGitHub: Boolean,
) {
    CALLBACK_ONLY("Callback only", usesNotion = false, usesGitHub = false),
    NOTION("Notion", usesNotion = true, usesGitHub = false),
    GITHUB("GitHub", usesNotion = false, usesGitHub = true),
    BOTH("Notion + GitHub", usesNotion = true, usesGitHub = true),
}
