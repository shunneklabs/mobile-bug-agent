package dev.sunnat629.mba.sample

import android.content.Context
import dev.sunnat629.mba.android.MBAAndroid
import dev.sunnat629.mba.agent.runtime.MBAAgentBatchCallback
import dev.sunnat629.mba.agent.runtime.MBAAgentCallback
import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.config.LLM
import dev.sunnat629.mba.core.config.LLMConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private val defaultSettings: SampleProcessingSettings
        get() = SampleProcessingSettings(
            deliveryMode = BuildConfig.MBA_SAMPLE_MODE.toDeliveryMode(),
            useAgent = BuildConfig.MBA_SAMPLE_USE_AGENT.toBooleanStrictOrNull() ?: true,
        )

    private val _settings = MutableStateFlow(defaultSettings)
    val settings: StateFlow<SampleProcessingSettings> = _settings.asStateFlow()

    val deliveryMode: SampleDeliveryMode get() = _settings.value.deliveryMode

    val hasLlmConfig: Boolean
        get() = llmConfig() != null

    val llmLabel: String
        get() = buildString {
            append(BuildConfig.MBA_SAMPLE_LLM_PROVIDER.ifBlank { "GEMINI" }.uppercase())
            BuildConfig.MBA_SAMPLE_LLM_MODEL.ifBlank { null }?.let { append(" / ").append(it) }
        }

    fun llmConfig(): LLMConfig? {
        val provider = BuildConfig.MBA_SAMPLE_LLM_PROVIDER.ifBlank { "GEMINI" }.uppercase()
        val apiKey = BuildConfig.MBA_SAMPLE_LLM_API_KEY
        val endpoint = BuildConfig.MBA_SAMPLE_LLM_ENDPOINT.ifBlank { null }
        val model = BuildConfig.MBA_SAMPLE_LLM_MODEL.ifBlank { null }
        val base = when (provider) {
            LLM.Provider.GEMINI.name -> LLM.gemini(apiKey, endpoint = endpoint)
            LLM.Provider.OPENAI.name -> LLM.openAI(apiKey, endpoint = endpoint)
            LLM.Provider.ANTHROPIC.name -> LLM.anthropic(apiKey, endpoint = endpoint)
            LLM.Provider.OLLAMA.name -> LLM.ollama(endpoint = endpoint ?: "http://10.0.2.2:11434")
            LLM.Provider.OPENROUTER.name -> LLM.openRouter(apiKey, endpoint = endpoint)
            LLM.Provider.MISTRAL.name -> LLM.mistral(apiKey, endpoint = endpoint)
            LLM.Provider.DEEPSEEK.name -> LLM.deepSeek(apiKey, endpoint = endpoint)
            LLM.Provider.DASHSCOPE.name -> LLM.dashScope(apiKey, endpoint = endpoint)
            LLM.Provider.CUSTOM.name -> {
                if (endpoint == null || model == null) return null
                LLM.custom(apiKey = apiKey, endpoint = endpoint, model = model)
            }
            else -> return null
        }
        if (base.requiresApiKey && apiKey.isBlank()) return null
        return model?.let(base::model) ?: base
    }

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
            llm = llmConfig(),
            useAgent = appliedSettings.useAgent,
            callback = MBAAgentCallback { event ->
                MBALog.i(
                    TAG,
                    "SDKOnly latest callback: group=${event.group.id}, new=${event.isNewGroup}, " +
                        "agentic=${event.agentic}, source=${event.analysisSource}, " +
                        "title='${event.report.title}', severity=${event.report.severity}",
                )
            },
            batchCallback = MBAAgentBatchCallback { batch ->
                MBALog.i(
                    TAG,
                    "SDKOnly batch callback: latest=${batch.latest.group.id}, " +
                        "events=${batch.totalCount}, success=${batch.successCount}, failed=${batch.failCount}",
                )
            },
            jsonCallback = { json ->
                logChunkedJson("App-layer latest callback JSON", json)
            },
            batchJsonCallback = { json ->
                logChunkedJson("App-layer batch callback JSON", json)
            },
            debug = true,
        )
        return appliedSettings
    }

    private fun SampleProcessingSettings.normalized(): SampleProcessingSettings =
        if (useAgent && !hasLlmConfig) copy(useAgent = false) else this

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
