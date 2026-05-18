package dev.sunnat629.mba.sample

import android.content.Context
import dev.sunnat629.mba.android.MBAAndroid
import dev.sunnat629.mba.agent.runtime.MBAAgentBatchCallback
import dev.sunnat629.mba.agent.runtime.MBAAgentCallback
import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.config.LLM
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "Sample"
private const val PREFS_NAME = "mba_sample_runtime"
private const val KEY_DELIVERY_MODE = "delivery_mode"
private const val KEY_USE_AGENT = "use_agent"
private const val LOG_CHUNK_SIZE = 3500

data class SampleProcessingSettings(
    val deliveryMode: SampleDeliveryMode,
    val useAgent: Boolean,
)

object SampleRuntime {
    private val eventJson = Json {
        encodeDefaults = true
        prettyPrint = true
    }

    private val defaultSettings: SampleProcessingSettings
        get() = SampleProcessingSettings(
            deliveryMode = BuildConfig.MBA_SAMPLE_MODE.toDeliveryMode(),
            useAgent = BuildConfig.MBA_SAMPLE_USE_AGENT.toBooleanStrictOrNull() ?: true,
        )

    private val _settings = MutableStateFlow(defaultSettings)
    val settings: StateFlow<SampleProcessingSettings> = _settings.asStateFlow()

    val deliveryMode: SampleDeliveryMode get() = _settings.value.deliveryMode

    fun restore(context: Context): SampleProcessingSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaults = defaultSettings
        val restored = SampleProcessingSettings(
            deliveryMode = prefs.getString(KEY_DELIVERY_MODE, null)
                ?.let { value -> SampleDeliveryMode.entries.firstOrNull { it.name == value } }
                ?: defaults.deliveryMode,
            useAgent = if (prefs.contains(KEY_USE_AGENT)) {
                prefs.getBoolean(KEY_USE_AGENT, defaults.useAgent)
            } else {
                defaults.useAgent
            },
        ).normalized()
        apply(context, restored, persist = false)
        return restored
    }

    fun selectDeliveryMode(context: Context, mode: SampleDeliveryMode): SampleProcessingSettings =
        apply(context, _settings.value.copy(deliveryMode = mode), persist = true)

    fun setUseAgent(context: Context, useAgent: Boolean): SampleProcessingSettings =
        apply(context, _settings.value.copy(useAgent = useAgent), persist = true)

    fun resetToBuildDefaults(context: Context): SampleProcessingSettings =
        apply(context, defaultSettings, persist = true)

    private fun apply(
        context: Context,
        settings: SampleProcessingSettings,
        persist: Boolean,
    ): SampleProcessingSettings {
        val appContext = context.applicationContext
        val appliedSettings = settings.normalized()
        if (persist) {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DELIVERY_MODE, appliedSettings.deliveryMode.name)
                .putBoolean(KEY_USE_AGENT, appliedSettings.useAgent)
                .apply()
        }

        _settings.value = appliedSettings
        MBAAndroid.saveConfig(
            context = appContext,
            backendEndpoint = BuildConfig.MBA_BACKEND_ENDPOINT,
            projectKey = "sample-app-debug",
            serverApiKey = BuildConfig.MBA_SERVER_API_KEY,
            sendToBackend = appliedSettings.deliveryMode == SampleDeliveryMode.HOSTED,
            llm = if (BuildConfig.GEMINI_API_KEY.isBlank()) null else LLM.gemini(BuildConfig.GEMINI_API_KEY),
            useAgent = appliedSettings.useAgent,
            callback = MBAAgentCallback { event ->
                val callbackJson = eventJson.encodeToString(event)
                MBALog.i(
                    TAG,
                    "SDKOnly latest callback: group=${event.group.id}, new=${event.isNewGroup}, " +
                        "agentic=${event.agentic}, source=${event.analysisSource}, " +
                        "title='${event.report.title}', severity=${event.report.severity}",
                )
                logChunkedJson("App-layer latest callback JSON", callbackJson)
            },
            batchCallback = MBAAgentBatchCallback { batch ->
                val batchJson = eventJson.encodeToString(batch)
                MBALog.i(
                    TAG,
                    "SDKOnly batch callback: latest=${batch.latest.group.id}, " +
                        "events=${batch.totalCount}, success=${batch.successCount}, failed=${batch.failCount}",
                )
                logChunkedJson("App-layer batch callback JSON", batchJson)
            },
            debug = true,
        )
        return appliedSettings
    }

    private fun SampleProcessingSettings.normalized(): SampleProcessingSettings =
        if (useAgent && BuildConfig.GEMINI_API_KEY.isBlank()) copy(useAgent = false) else this

    private fun logChunkedJson(label: String, json: String) {
        MBALog.i(TAG, "$label:")
        json.chunked(LOG_CHUNK_SIZE).forEachIndexed { index, chunk ->
            MBALog.i(TAG, "$label[$index]: $chunk")
        }
    }

    private fun String.toDeliveryMode(): SampleDeliveryMode =
        if (equals("sdkOnly", ignoreCase = true) || equals("sdk-only", ignoreCase = true)) {
            SampleDeliveryMode.SDK_ONLY
        } else {
            SampleDeliveryMode.HOSTED
        }
}

enum class SampleDeliveryMode(val label: String) {
    SDK_ONLY("SDKOnly"),
    HOSTED("Hosted backend"),
}
