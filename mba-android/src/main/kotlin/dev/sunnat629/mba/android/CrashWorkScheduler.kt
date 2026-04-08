package dev.sunnat629.mba.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal object CrashWorkScheduler {
    private const val UNIQUE_WORK = "mba.process.pending.crashes"

    fun enqueue(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val req = OneTimeWorkRequestBuilder<CrashUploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.SECONDS,
            )
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, req)
    }
}
