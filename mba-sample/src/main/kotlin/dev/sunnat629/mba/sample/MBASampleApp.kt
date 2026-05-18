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
        MBALog.d(TAG, "GEMINI_API_KEY:      ${if (BuildConfig.GEMINI_API_KEY.isNotBlank()) "loaded" else "!! EMPTY !!" }")
        MBALog.d(TAG, "MBA_BACKEND_ENDPOINT: ${BuildConfig.MBA_BACKEND_ENDPOINT}")
        MBALog.d(TAG, "MBA_SERVER_API_KEY: ${if (BuildConfig.MBA_SERVER_API_KEY.isNotBlank()) "loaded" else "!! EMPTY !!" }")
        MBALog.d(TAG, "MBA_SAMPLE_MODE: ${BuildConfig.MBA_SAMPLE_MODE}")
        MBALog.d(TAG, "MBA_SAMPLE_USE_AGENT: ${BuildConfig.MBA_SAMPLE_USE_AGENT}")
        MBALog.d(TAG, "========================================")

        // Startup may already install the crash handler. Calling explicitly keeps
        // the sample correct when AndroidX Startup is disabled by a host app.
        MBAAndroid.install(this)

        val settings = SampleRuntime.restore(this)
        val mode = settings.deliveryMode

        MBA.configure(
            MBAConfig.Builder().apply {
                this.mode = MBAMode.SdkOnly(llm = SampleRuntime.geminiConfig)
                debug = true
                useAgent = settings.useAgent
                autoFix = false
                skipNotion = false
            }.build()
        )

        // Restore app-layer integrations before WorkManager processes pending crashes.
        val integrationMode = SampleIntegrationRuntime.restore(this)

        // Process pending crashes only after config and optional sinks are ready.
        MBAAndroid.flushPendingCrashes(this)

        MBALog.d(TAG, "MBA SDK initialized in ${mode.label} with ${integrationMode.label}. Crashes are processed on next launch.")
    }
}
