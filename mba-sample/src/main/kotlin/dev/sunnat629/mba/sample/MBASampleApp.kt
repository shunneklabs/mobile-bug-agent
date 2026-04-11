package dev.sunnat629.mba.sample

import android.app.Application
import android.util.Log
import dev.sunnat629.mba.core.MBA
import dev.sunnat629.mba.core.config.MBAConfig
import dev.sunnat629.mba.core.config.MBAMode

class MBASampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val crashDir = filesDir.resolve("mba-crashes").absolutePath

        // Debug: verify BuildConfig values loaded from local.properties
        Log.d("MBA-Sample", "========================================")
        Log.d("MBA-Sample", "NOTION_API_KEY: ${if (BuildConfig.NOTION_API_KEY.isNotBlank()) "loaded (${BuildConfig.NOTION_API_KEY.take(12)}...)" else "!! EMPTY !!" }")
        Log.d("MBA-Sample", "NOTION_DB_ID:   ${if (BuildConfig.NOTION_DB_ID.isNotBlank()) "loaded (${BuildConfig.NOTION_DB_ID.take(12)}...)" else "!! EMPTY !!" }")
        Log.d("MBA-Sample", "GEMINI_API_KEY: ${if (BuildConfig.GEMINI_API_KEY.isNotBlank()) "loaded" else "!! EMPTY !!" }")
        Log.d("MBA-Sample", "Crash dir: $crashDir")
        Log.d("MBA-Sample", "========================================")

        // Phase 1: Install crash handler
        MBA.install(crashDir = crashDir)

        // Phase 2: Configure with debug=true so ALL internal SDK logs show in Logcat
        // Filter Logcat by "MBA" to see them.
        MBA.configure(
            MBAConfig.Builder().apply {
                mode = MBAMode.Saas(projectKey = "sample-app-debug")
                debug = true // <-- THIS enables all MBA/Kermit logging
            }.build()
        )

        Log.d("MBA-Sample", "MBA SDK initialized with debug logging ON. Filter Logcat by 'MBA' to see logs.")
    }
}
