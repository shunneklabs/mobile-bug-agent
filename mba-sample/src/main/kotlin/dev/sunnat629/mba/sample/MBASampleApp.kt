package dev.sunnat629.mba.sample

import android.app.Application
import dev.sunnat629.mba.android.MBAAndroid
import dev.sunnat629.mba.agent.runtime.MBAAgentCallback
import dev.sunnat629.mba.core.MBA
import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.config.LLM
import dev.sunnat629.mba.core.config.MBAConfig
import dev.sunnat629.mba.core.config.MBAMode

private const val TAG = "Sample"
private val sampleDeliveryMode: SampleDeliveryMode
    get() = if (BuildConfig.MBA_SAMPLE_MODE.equals("sdkOnly", ignoreCase = true) ||
        BuildConfig.MBA_SAMPLE_MODE.equals("sdk-only", ignoreCase = true)
    ) {
        SampleDeliveryMode.SDK_ONLY
    } else {
        SampleDeliveryMode.HOSTED
    }

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
        MBALog.d(TAG, "========================================")

        val mode = sampleDeliveryMode

        MBA.configure(
            MBAConfig.Builder().apply {
                this.mode = when (mode) {
                    SampleDeliveryMode.SDK_ONLY -> MBAMode.SdkOnly(llmApiKey = BuildConfig.GEMINI_API_KEY)
                    SampleDeliveryMode.HOSTED -> MBAMode.Saas(projectKey = "sample-app-debug")
                }
                debug = true
                autoFix = mode == SampleDeliveryMode.HOSTED
                skipNotion = false
            }.build()
        )

        // Restore app-layer integrations before WorkManager processes pending crashes.
        val integrationMode = SampleIntegrationRuntime.restore(this)

        // Save local processing/backend config before enqueueing WorkManager.
        // Emulator default is 10.0.2.2. Physical devices must use the Mac LAN URL,
        // for example MBA_SAMPLE_BACKEND_ENDPOINT=http://192.168.1.42:8080.
        MBAAndroid.saveConfig(
            context = this,
            backendEndpoint = BuildConfig.MBA_BACKEND_ENDPOINT,
            projectKey = "sample-app-debug",
            serverApiKey = BuildConfig.MBA_SERVER_API_KEY,
            sendToBackend = mode == SampleDeliveryMode.HOSTED,
            llm = if (BuildConfig.GEMINI_API_KEY.isBlank()) null else LLM.gemini(BuildConfig.GEMINI_API_KEY),
            callback = MBAAgentCallback { event ->
                MBALog.i(
                    TAG,
                    "SDKOnly callback: group=${event.group.id}, new=${event.isNewGroup}, " +
                        "title='${event.report.title}', severity=${event.report.severity}",
                )
            },
            debug = true,
        )

        // Install crash handler + enqueue WorkManager after config and sinks are ready.
        MBAAndroid.install(this)

        MBALog.d(TAG, "MBA SDK initialized in ${mode.label} with ${integrationMode.label}. Crashes are processed on next launch.")
    }
}

enum class SampleDeliveryMode(val label: String) {
    SDK_ONLY("SDKOnly"),
    HOSTED("Hosted backend"),
}

object SampleRuntime {
    val deliveryMode: SampleDeliveryMode get() = sampleDeliveryMode
}
