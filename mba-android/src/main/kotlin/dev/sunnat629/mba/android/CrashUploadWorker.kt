package dev.sunnat629.mba.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * WorkManager worker that uploads processed crash reports.
 *
 * MVP scaffold; actual network/store integration will be wired after Notion/GitHub backends land.
 */
internal class CrashUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // TODO: fetch pending processed reports and upload.
        return Result.success()
    }
}
