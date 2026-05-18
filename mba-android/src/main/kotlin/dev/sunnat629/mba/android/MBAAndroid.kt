package dev.sunnat629.mba.android

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.work.*
import dev.sunnat629.mba.agent.runtime.MBAAgentBatchCallback
import dev.sunnat629.mba.agent.runtime.MBAAgentBatchEvent
import dev.sunnat629.mba.agent.runtime.MBAAgentCallback
import dev.sunnat629.mba.agent.runtime.MBAAgentEvent
import dev.sunnat629.mba.agent.runtime.MBAAgentSink
import dev.sunnat629.mba.agent.runtime.TicketBackendAgentSink
import dev.sunnat629.mba.core.MBA
import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.config.LLMConfig
import dev.sunnat629.mba.core.ticket.TicketBackend
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * Android-specific entry point for the MBA SDK.
 *
 * Call [install] as early as possible (Application.onCreate).
 * This:
 * 1. Sets the UncaughtExceptionHandler
 * 2. Installs the core crash handler (MBA.install)
 * 3. Enqueues CrashUploadWorker via WorkManager to process any
 *    crash files from previous sessions
 */
public object MBAAndroid {

    private const val TAG = "MBAAndroid"
    private const val WORK_NAME = "mba-crash-upload"

    private var isInstalled = false

    @Volatile
    internal var agentCallback: MBAAgentCallback? = null
        private set

    @Volatile
    internal var agentBatchCallback: MBAAgentBatchCallback? = null
        private set

    @Volatile
    internal var agentJsonCallback: (suspend (String) -> Unit)? = null
        private set

    @Volatile
    internal var agentBatchJsonCallback: (suspend (String) -> Unit)? = null
        private set

    @Volatile
    internal var notionSink: MBAAgentSink? = null
        private set

    @Volatile
    internal var githubSink: MBAAgentSink? = null
        private set

    @Volatile
    internal var fallbackTicketBackend: TicketBackend? = null
        private set

    private val eventJson = Json { ignoreUnknownKeys = true }
    private val _agentEvents = MutableSharedFlow<MBAAgentEvent>(extraBufferCapacity = 64)
    private val _agentEventJson = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val _agentEventBatches = MutableSharedFlow<MBAAgentBatchEvent>(extraBufferCapacity = 16)
    private val _agentBatchEventJson = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /**
     * SDKOnly analysis events emitted after the local Koog agent processes a crash.
     *
     * Apps can collect this flow to handle their own upload, UI, analytics, or
     * custom ticket routing without using built-in Notion/GitHub sinks.
     */
    public val agentEvents: SharedFlow<MBAAgentEvent> = _agentEvents.asSharedFlow()

    /** Same SDKOnly event stream serialized as JSON for app-owned integrations. */
    public val agentEventJson: SharedFlow<String> = _agentEventJson.asSharedFlow()

    /**
     * Batch stream emitted once per worker run. [MBAAgentBatchEvent.latest] is
     * the default app-facing callback event, while [MBAAgentBatchEvent.events]
     * preserves every processed pending crash for apps that need full history.
     */
    public val agentEventBatches: SharedFlow<MBAAgentBatchEvent> = _agentEventBatches.asSharedFlow()

    /** Same SDKOnly batch stream serialized as JSON for app-owned integrations. */
    public val agentBatchEventJson: SharedFlow<String> = _agentBatchEventJson.asSharedFlow()

    /**
     * Install the MBA SDK for Android.
     *
     * Idempotent — second call is a no-op.
     * Call from Application.onCreate() or via AndroidX Startup (MBAInitializer).
     */
    public fun install(context: Context) {
        if (isInstalled) return
        isInstalled = true

        val appContext = context.applicationContext

        val metadata = androidAppMetadata(appContext)
        MBA.setGlobalMetadata(metadata)

        // 1. Install core crash handler. Metadata is injected through MBA global metadata
        // so we do not install a second Android handler that would write duplicate crash files.
        val crashDir = appContext.filesDir.resolve("mba-crashes").absolutePath
        MBA.install(crashDir)

        // 2. Detect ANR process deaths from the previous run. ANRs are not thrown
        // exceptions, so they must be reconstructed from Android exit history
        // after the app starts again.
        ANRExitReporter.capturePreviousAnrIfAny(appContext, crashDir, metadata)

        // 3. Enqueue WorkManager to process pending crashes from previous sessions
        enqueueCrashUploadWorker(appContext)

        MBALog.i(TAG, "MBAAndroid installed. WorkManager enqueued for pending crashes.")
    }

    private fun androidAppMetadata(context: Context): Map<String, String> {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionName = packageInfo.versionName ?: "unknown"
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
            packageInfo.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toString()
        }
        val buildType = if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) "debug" else "release"
        return mapOf(
            "appId" to context.packageName,
            "applicationId" to context.packageName,
            "android.package_name" to context.packageName,
            "android.app_version" to versionName,
            "android.version_code" to versionCode,
            "android.build_type" to buildType,
            "android.sdk_int" to android.os.Build.VERSION.SDK_INT.toString(),
            "android.os_version" to (android.os.Build.VERSION.RELEASE ?: "unknown"),
            "android.device_model" to android.os.Build.MODEL,
            "android.device_manufacturer" to android.os.Build.MANUFACTURER,
        )
    }

    /**
     * Save processing config to SharedPreferences so the WorkManager worker can access it.
     *
     * Called by the app after MBA.configure() — typically in Application.onCreate().
     *
     * ```kotlin
     * MBA.configure(config)
     * MBAAndroid.saveConfig(
     *     context = this,
     *     backendEndpoint = "http://10.0.2.2:8080",
     *     projectKey = "sample-app-debug",
     *     sendToBackend = true,
     *     debug = true,
     * )
     * ```
     */
    public fun saveConfig(
        context: Context,
        backendEndpoint: String? = null,
        projectKey: String? = null,
        serverApiKey: String? = null,
        sendToBackend: Boolean = backendEndpoint != null,
        llm: LLMConfig? = null,
        useAgent: Boolean = true,
        callback: MBAAgentCallback? = null,
        batchCallback: MBAAgentBatchCallback? = null,
        jsonCallback: (suspend (String) -> Unit)? = null,
        batchJsonCallback: (suspend (String) -> Unit)? = null,
        debug: Boolean = false,
    ) {
        val appContext = context.applicationContext
        val crashDir = appContext.filesDir.resolve("mba-crashes").absolutePath

        MBAPreferences.save(
            context = appContext,
            crashDir = crashDir,
            backendEndpoint = backendEndpoint,
            projectKey = projectKey,
            serverApiKey = serverApiKey,
            sendToBackend = sendToBackend,
            llmProvider = llm?.provider?.name,
            llmApiKey = llm?.apiKey,
            llmModel = llm?.model,
            useAgent = useAgent,
            debug = debug,
        )
        agentCallback = callback
        agentBatchCallback = batchCallback
        agentJsonCallback = jsonCallback
        agentBatchJsonCallback = batchJsonCallback

        MBALog.i(TAG, "MBA processing config saved to SharedPreferences for WorkManager")
    }

    public fun setAgentCallback(callback: MBAAgentCallback?) {
        agentCallback = callback
    }

    public fun setAgentBatchCallback(callback: MBAAgentBatchCallback?) {
        agentBatchCallback = callback
    }

    public fun setAgentJsonCallback(callback: (suspend (String) -> Unit)?) {
        agentJsonCallback = callback
    }

    public fun setAgentBatchJsonCallback(callback: (suspend (String) -> Unit)?) {
        agentBatchJsonCallback = callback
    }

    /**
     * Register optional SDKOnly sinks supplied by optional modules such as
     * `mba-notion` or `mba-github`.
     */
    public fun setExternalSinks(
        notionSink: MBAAgentSink? = null,
        githubSink: MBAAgentSink? = null,
        fallbackTicketBackend: TicketBackend? = null,
    ) {
        this.notionSink = notionSink
        this.githubSink = githubSink
        this.fallbackTicketBackend = fallbackTicketBackend
    }

    /**
     * Convenience wrapper for optional ticket backend modules.
     *
     * Example: pass `NotionTicketBackend` and/or `GitHubIssueBackend` from the
     * app after adding `mba-notion` / `mba-github` dependencies.
     */
    public fun setTicketBackends(
        notionBackend: TicketBackend? = null,
        githubBackend: TicketBackend? = null,
        fallbackTicketBackend: TicketBackend? = notionBackend,
    ) {
        setExternalSinks(
            notionSink = notionBackend?.let(::TicketBackendAgentSink),
            githubSink = githubBackend?.let(::TicketBackendAgentSink),
            fallbackTicketBackend = fallbackTicketBackend,
        )
    }

    internal suspend fun publishAgentEvent(event: MBAAgentEvent, notifyAppCallback: Boolean = true) {
        val json = eventJson.encodeToString(event)
        _agentEvents.emit(event)
        _agentEventJson.emit(json)
        if (notifyAppCallback) {
            logAgentEventJson(json)
        }
        if (notifyAppCallback) {
            agentCallback?.onCrashAnalyzed(event)
            agentJsonCallback?.invoke(json)
        }
    }

    internal suspend fun publishAgentBatchEvent(batch: MBAAgentBatchEvent) {
        val json = eventJson.encodeToString(batch)
        val latestJson = eventJson.encodeToString(batch.latest)
        _agentEventBatches.emit(batch)
        _agentBatchEventJson.emit(json)
        logAgentBatchEventJson(json)
        agentCallback?.onCrashAnalyzed(batch.latest)
        agentBatchCallback?.onCrashesAnalyzed(batch)
        agentJsonCallback?.invoke(latestJson)
        agentBatchJsonCallback?.invoke(json)
    }

    private fun logAgentEventJson(json: String) {
        MBALog.d(TAG, "SDKOnly event JSON:")
        json.chunked(LOG_CHUNK_SIZE).forEachIndexed { index, chunk ->
            MBALog.d(TAG, "SDKOnly event JSON[$index]: $chunk")
        }
    }

    private fun logAgentBatchEventJson(json: String) {
        MBALog.d(TAG, "SDKOnly callback batch JSON:")
        json.chunked(LOG_CHUNK_SIZE).forEachIndexed { index, chunk ->
            MBALog.d(TAG, "SDKOnly callback batch JSON[$index]: $chunk")
        }
    }

    private fun enqueueCrashUploadWorker(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<CrashUploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP, // Don't duplicate if already running
            workRequest,
        )

        MBALog.d(TAG, "CrashUploadWorker enqueued (unique: $WORK_NAME, policy: KEEP)")
    }

    private const val LOG_CHUNK_SIZE = 3500
}
