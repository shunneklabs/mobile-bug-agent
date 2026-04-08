package dev.sunnat629.mba.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * WorkManager worker that processes and syncs pending crash reports.
 */
internal class CrashUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val config = MBAAndroidRuntime.config
        val crashStore = MBAAndroidRuntime.crashStore
        val ticketBackend = MBAAndroidRuntime.ticketBackend

        if (config == null || crashStore == null || ticketBackend == null) {
            // Not configured yet; retry later.
            return Result.retry()
        }

        return try {
            PendingCrashProcessor.processInternal(
                context = applicationContext,
                config = config,
                crashStore = crashStore,
                ticketBackend = ticketBackend,
            )
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
    }
}
