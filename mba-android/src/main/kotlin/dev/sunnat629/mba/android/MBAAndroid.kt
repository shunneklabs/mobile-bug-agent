package dev.sunnat629.mba.android

import android.content.Context
import androidx.work.*
import dev.sunnat629.mba.android.capture.MBACrashHandler
import dev.sunnat629.mba.core.MBA
import dev.sunnat629.mba.core.MBALog
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

        // 1. Install UncaughtExceptionHandler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        val mbaHandler = MBACrashHandler(appContext, defaultHandler)
        Thread.setDefaultUncaughtExceptionHandler(mbaHandler)

        // 2. Install core crash handler
        val crashDir = appContext.filesDir.resolve("mba-crashes").absolutePath
        MBA.install(crashDir)

        // 3. Enqueue WorkManager to process pending crashes from previous sessions
        enqueueCrashUploadWorker(appContext)

        MBALog.i(TAG, "MBAAndroid installed. WorkManager enqueued for pending crashes.")
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
     *     debug = true,
     * )
     * ```
     */
    public fun saveConfig(
        context: Context,
        notionApiKey: String,
        notionTicketDbId: String,
        notionCrashDbId: String? = null,
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
            debug = debug,
        )

        MBALog.i(TAG, "Notion config saved to SharedPreferences for WorkManager")
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
