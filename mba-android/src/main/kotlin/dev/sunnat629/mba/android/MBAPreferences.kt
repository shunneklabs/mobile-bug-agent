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
 * - mba_notion_api_key
 * - mba_notion_ticket_db_id
 * - mba_notion_crash_db_id
 * - mba_debug
 */
internal object MBAPreferences {

    private const val TAG = "Prefs"
    private const val PREFS_NAME = "mba_sdk_config"

    private const val KEY_CRASH_DIR = "mba_crash_dir"
    private const val KEY_NOTION_API_KEY = "mba_notion_api_key"
    private const val KEY_NOTION_TICKET_DB_ID = "mba_notion_ticket_db_id"
    private const val KEY_NOTION_CRASH_DB_ID = "mba_notion_crash_db_id"
    private const val KEY_DEBUG = "mba_debug"

    fun save(
        context: Context,
        crashDir: String,
        notionApiKey: String,
        notionTicketDbId: String,
        notionCrashDbId: String?,
        debug: Boolean,
    ) {
        prefs(context).edit()
            .putString(KEY_CRASH_DIR, crashDir)
            .putString(KEY_NOTION_API_KEY, notionApiKey)
            .putString(KEY_NOTION_TICKET_DB_ID, notionTicketDbId)
            .putString(KEY_NOTION_CRASH_DB_ID, notionCrashDbId ?: "")
            .putBoolean(KEY_DEBUG, debug)
            .apply()
        MBALog.i(TAG, "Config saved to SharedPreferences")
    }

    fun loadCrashDir(context: Context): String? =
        prefs(context).getString(KEY_CRASH_DIR, null)

    fun loadNotionApiKey(context: Context): String? =
        prefs(context).getString(KEY_NOTION_API_KEY, null)

    fun loadNotionTicketDbId(context: Context): String? =
        prefs(context).getString(KEY_NOTION_TICKET_DB_ID, null)

    fun loadNotionCrashDbId(context: Context): String? =
        prefs(context).getString(KEY_NOTION_CRASH_DB_ID, null)?.ifBlank { null }

    fun loadDebug(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEBUG, false)

    fun isConfigured(context: Context): Boolean =
        loadNotionApiKey(context)?.isNotBlank() == true &&
        loadNotionTicketDbId(context)?.isNotBlank() == true

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
