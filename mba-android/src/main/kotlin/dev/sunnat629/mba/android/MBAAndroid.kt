package dev.sunnat629.mba.android

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.work.*
import dev.sunnat629.mba.agent.runtime.MBAAgentCallback
import dev.sunnat629.mba.core.MBA
import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.config.LLMConfig
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

        MBA.setGlobalMetadata(androidAppMetadata(appContext))

        // 1. Install core crash handler. Metadata is injected through MBA global metadata
        // so we do not install a second Android handler that would write duplicate crash files.
        val crashDir = appContext.filesDir.resolve("mba-crashes").absolutePath
        MBA.install(crashDir)

        // 2. Enqueue WorkManager to process pending crashes from previous sessions
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
     * Save Notion config to SharedPreferences so the WorkManager worker can access it.
     *
     * Called by the app after MBA.configure() — typically in Application.onCreate().
     *
     * ```kotlin
     * MBA.configure(config)
     * MBAAndroid.saveConfig(
     *     context = this,
     *     notionApiKey = "secret_...",
     *     notionTicketDbId = "...",
     *     notionCrashDbId = "...",
     *     backendEndpoint = "http://10.0.2.2:8080",
     *     projectKey = "sample-app-debug",
     *     sendToBackend = true,
     *     debug = true,
     * )
     * ```
     */
    public fun saveConfig(
        context: Context,
        notionApiKey: String,
        notionTicketDbId: String,
        notionCrashDbId: String? = null,
        backendEndpoint: String? = null,
        projectKey: String? = null,
        serverApiKey: String? = null,
        sendToBackend: Boolean = backendEndpoint != null,
        llm: LLMConfig? = null,
        skipGitIssue: Boolean = true,
        githubToken: String? = null,
        githubOwner: String? = null,
        githubRepo: String? = null,
        callback: MBAAgentCallback? = null,
        debug: Boolean = false,
    ) {
        val appContext = context.applicationContext
        val crashDir = appContext.filesDir.resolve("mba-crashes").absolutePath

        MBAPreferences.save(
            context = appContext,
            crashDir = crashDir,
            notionApiKey = notionApiKey,
            notionTicketDbId = notionTicketDbId,
            notionCrashDbId = notionCrashDbId,
            backendEndpoint = backendEndpoint,
            projectKey = projectKey,
            serverApiKey = serverApiKey,
            sendToBackend = sendToBackend,
            llmProvider = llm?.provider?.name,
            llmApiKey = llm?.apiKey,
            llmModel = llm?.model,
            skipGitIssue = skipGitIssue,
            githubToken = githubToken,
            githubOwner = githubOwner,
            githubRepo = githubRepo,
            debug = debug,
        )
        agentCallback = callback

        MBALog.i(TAG, "Notion/backend config saved to SharedPreferences for WorkManager")
    }

    public fun setAgentCallback(callback: MBAAgentCallback?) {
        agentCallback = callback
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
}
