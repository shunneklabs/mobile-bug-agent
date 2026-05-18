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
        MBALog.d(TAG, "MBA_BACKEND_ENDPOINT: ${BuildConfig.MBA_BACKEND_ENDPOINT}")
        MBALog.d(TAG, "MBA_SERVER_API_KEY: ${if (BuildConfig.MBA_SERVER_API_KEY.isNotBlank()) "loaded" else "!! EMPTY !!" }")
        MBALog.d(TAG, "MBA_SAMPLE_MODE: ${BuildConfig.MBA_SAMPLE_MODE}")
        MBALog.d(TAG, "MBA_SAMPLE_USE_AGENT: ${BuildConfig.MBA_SAMPLE_USE_AGENT}")
        MBALog.d(TAG, "========================================")

        val settings = SampleRuntime.restore(this)
        val mode = settings.deliveryMode

        MBA.configure(
            MBAConfig.Builder().apply {
                this.mode = when (mode) {
                    SampleDeliveryMode.SDK_ONLY -> MBAMode.SdkOnly(llmApiKey = BuildConfig.GEMINI_API_KEY)
                    SampleDeliveryMode.HOSTED -> MBAMode.Saas(projectKey = "sample-app-debug")
                }
                debug = true
                useAgent = settings.useAgent
                autoFix = mode == SampleDeliveryMode.HOSTED
                skipNotion = false
            }.build()
        )

        // Restore app-layer integrations before WorkManager processes pending crashes.
        val integrationMode = SampleIntegrationRuntime.restore(this)

        // Install crash handler + enqueue WorkManager after config and sinks are ready.
        MBAAndroid.install(this)

        MBALog.d(TAG, "MBA SDK initialized in ${mode.label} with ${integrationMode.label}. Crashes are processed on next launch.")
    }
}
