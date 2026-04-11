package dev.sunnat629.mba.sample

import android.app.Application
import android.util.Log
import dev.sunnat629.mba.android.MBAAndroid
import dev.sunnat629.mba.core.MBA
import dev.sunnat629.mba.core.config.MBAConfig
import dev.sunnat629.mba.core.config.MBAMode

class MBASampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        Log.d("MBA-Sample", "========================================")
        Log.d("MBA-Sample", "NOTION_API_KEY:      ${if (BuildConfig.NOTION_API_KEY.isNotBlank()) "loaded" else "!! EMPTY !!" }")
        Log.d("MBA-Sample", "NOTION_TICKET_DB_ID: ${if (BuildConfig.NOTION_TICKET_DB_ID.isNotBlank()) "loaded" else "!! EMPTY !!" }")
        Log.d("MBA-Sample", "NOTION_CRASH_DB_ID:  ${if (BuildConfig.NOTION_CRASH_DB_ID.isNotBlank()) "loaded" else "!! EMPTY !!" }")
        Log.d("MBA-Sample", "========================================")

        // Phase 1: Install crash handler + enqueue WorkManager
        MBAAndroid.install(this)

        // Phase 2: Configure MBA with debug logging
        MBA.configure(
            MBAConfig.Builder().apply {
                mode = MBAMode.Saas(projectKey = "sample-app-debug")
                debug = true
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

        Log.d("MBA-Sample", "MBA SDK initialized. Crashes will auto-push to Notion on next launch.")
    }
}
