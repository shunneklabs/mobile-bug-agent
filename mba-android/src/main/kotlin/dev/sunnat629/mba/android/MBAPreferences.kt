package dev.sunnat629.mba.android

import android.content.Context
import android.content.SharedPreferences
import dev.sunnat629.mba.core.MBALog

/**
 * Persists MBA config to SharedPreferences so CrashUploadWorker
 * can read it without the MBA singleton being initialized.
 *
 * Keys stored:
 * - mba_crash_dir
 * - mba_backend_endpoint
 * - mba_project_key
 * - mba_server_api_key
 * - mba_send_to_backend
 * - mba_llm_provider
 * - mba_llm_api_key
 * - mba_llm_model
 * - mba_use_agent
 * - mba_debug
 */
internal object MBAPreferences {

    private const val TAG = "Prefs"
    private const val PREFS_NAME = "mba_sdk_config"

    private const val KEY_CRASH_DIR = "mba_crash_dir"
    private const val KEY_BACKEND_ENDPOINT = "mba_backend_endpoint"
    private const val KEY_PROJECT_KEY = "mba_project_key"
    private const val KEY_SERVER_API_KEY = "mba_server_api_key"
    private const val KEY_SEND_TO_BACKEND = "mba_send_to_backend"
    private const val KEY_LLM_PROVIDER = "mba_llm_provider"
    private const val KEY_LLM_API_KEY = "mba_llm_api_key"
    private const val KEY_LLM_MODEL = "mba_llm_model"
    private const val KEY_USE_AGENT = "mba_use_agent"
    private const val KEY_DEBUG = "mba_debug"

    fun save(
        context: Context,
        crashDir: String,
        backendEndpoint: String?,
        projectKey: String?,
        serverApiKey: String?,
        sendToBackend: Boolean,
        llmProvider: String?,
        llmApiKey: String?,
        llmModel: String?,
        useAgent: Boolean,
        debug: Boolean,
    ) {
        prefs(context).edit()
            .putString(KEY_CRASH_DIR, crashDir)
            .putString(KEY_BACKEND_ENDPOINT, backendEndpoint ?: "")
            .putString(KEY_PROJECT_KEY, projectKey ?: "")
            .putString(KEY_SERVER_API_KEY, serverApiKey ?: "")
            .putBoolean(KEY_SEND_TO_BACKEND, sendToBackend)
            .putString(KEY_LLM_PROVIDER, llmProvider ?: "")
            .putString(KEY_LLM_API_KEY, llmApiKey ?: "")
            .putString(KEY_LLM_MODEL, llmModel ?: "")
            .putBoolean(KEY_USE_AGENT, useAgent)
            .putBoolean(KEY_DEBUG, debug)
            .apply()
        MBALog.i(TAG, "Config saved to SharedPreferences")
    }

    fun loadCrashDir(context: Context): String? =
        prefs(context).getString(KEY_CRASH_DIR, null)

    fun loadBackendEndpoint(context: Context): String? =
        prefs(context).getString(KEY_BACKEND_ENDPOINT, null)?.ifBlank { null }

    fun loadProjectKey(context: Context): String? =
        prefs(context).getString(KEY_PROJECT_KEY, null)?.ifBlank { null }

    fun loadServerApiKey(context: Context): String? =
        prefs(context).getString(KEY_SERVER_API_KEY, null)?.ifBlank { null }

    fun loadSendToBackend(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SEND_TO_BACKEND, loadBackendEndpoint(context) != null)

    fun loadLlmProvider(context: Context): String? =
        prefs(context).getString(KEY_LLM_PROVIDER, null)?.ifBlank { null }

    fun loadLlmApiKey(context: Context): String? =
        prefs(context).getString(KEY_LLM_API_KEY, null)?.ifBlank { null }

    fun loadLlmModel(context: Context): String? =
        prefs(context).getString(KEY_LLM_MODEL, null)?.ifBlank { null }

    fun loadUseAgent(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_AGENT, true)

    fun loadDebug(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEBUG, false)

    fun isConfigured(context: Context): Boolean =
        (loadSendToBackend(context) && loadBackendEndpoint(context) != null) ||
            (loadLlmApiKey(context)?.isNotBlank() == true)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
