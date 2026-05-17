package dev.sunnat629.mba.sample

import android.app.Application
import dev.sunnat629.mba.android.MBAAndroid
import dev.sunnat629.mba.core.MBA
import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.config.MBAConfig
import dev.sunnat629.mba.core.config.MBAMode

private const val TAG = "Sample"

class MBASampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Enable MBALog (Kermit) before MBA.configure so the initial banner is visible.
        MBALog.enabled = true
        MBALog.d(TAG, "========================================")
        MBALog.d(TAG, "NOTION_API_KEY:      ${if (BuildConfig.NOTION_API_KEY.isNotBlank()) "loaded" else "!! EMPTY !!" }")
        MBALog.d(TAG, "NOTION_TICKET_DB_ID: ${if (BuildConfig.NOTION_TICKET_DB_ID.isNotBlank()) "loaded" else "!! EMPTY !!" }")
        MBALog.d(TAG, "NOTION_CRASH_DB_ID:  ${if (BuildConfig.NOTION_CRASH_DB_ID.isNotBlank()) "loaded" else "!! EMPTY !!" }")
        MBALog.d(TAG, "========================================")

        // Phase 1: Install crash handler + enqueue WorkManager
        MBAAndroid.install(this)

        // Phase 2: Configure MBA with debug logging.
        //
        // autoFix=true + skipNotion=false → server runs BOTH paths for every crash
        // captured here:
        //   1. NotionTicketBackend.createTicket(...)   (audit trail)
        //   2. GitHubAutoFixOpener.openAutoFix(...)    (issue + autofix/issue-N-<slug> branch)
        // Severity gate still applies on the GitHub side (HIGH/CRITICAL only); LOW/MEDIUM
        // crashes silently downgrade to Notion-only.
        MBA.configure(
            MBAConfig.Builder().apply {
                mode = MBAMode.Saas(projectKey = "sample-app-debug")
                debug = true
                autoFix = true       // opt in to GitHub auto-fix path
                skipNotion = false   // keep Notion ticket as well — both fire
            }.build()
        )

        // Phase 3: Save Notion config for WorkManager worker
        // The worker runs in a separate context and needs these credentials.
        MBAAndroid.saveConfig(
            context = this,
            notionApiKey = BuildConfig.NOTION_API_KEY,
            notionTicketDbId = BuildConfig.NOTION_TICKET_DB_ID,
            notionCrashDbId = BuildConfig.NOTION_CRASH_DB_ID.ifBlank { null },
            debug = true,
        )

        MBALog.d(TAG, "MBA SDK initialized. Crashes will auto-push to Notion on next launch.")
    }
}
