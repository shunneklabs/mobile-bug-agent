package dev.sunnat629.mba.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.fingerprint.CrashFingerprint
import dev.sunnat629.mba.core.pii.PIISanitizer
import dev.sunnat629.mba.core.store.LocalDedupCache
import dev.sunnat629.mba.notion.NotionTicketBackend
import kotlin.time.Duration.Companion.hours

/**
 * WorkManager worker that processes pending crash files and pushes them to Notion.
 *
 * Lifecycle:
 * 1. Reads config from SharedPreferences (MBAPreferences)
 * 2. Reads pending crash JSON files from disk (PendingCrashProcessor)
 * 3. For each file:
 *    a. Build ProcessedCrashReport (PII scrub, fingerprint, basic title — no AI)
 *    b. Dedup check (skip if fingerprint already seen)
 *    c. Push to Notion (Bug Tickets + Crash Reports DBs)
 *    d. Delete file on success
 * 4. Returns Result.success() or Result.retry()
 *
 * Triggered by MBAAndroid.install() on every app startup.
 * Constraints: NetworkType.CONNECTED, exponential backoff.
 */
internal class CrashUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private companion object {
        const val TAG = "UploadWorker"
    }

    override suspend fun doWork(): Result {
        MBALog.i(TAG, "CrashUploadWorker started (attempt ${runAttemptCount + 1})")

        // 1. Load config from SharedPreferences
        val context = applicationContext
        if (!MBAPreferences.isConfigured(context)) {
            MBALog.e(TAG, "MBA not configured — no Notion credentials in SharedPreferences. Failing.")
            return Result.failure()
        }

        val crashDir = MBAPreferences.loadCrashDir(context)
        val notionApiKey = MBAPreferences.loadNotionApiKey(context)
        val ticketDbId = MBAPreferences.loadNotionTicketDbId(context)
        val crashDbId = MBAPreferences.loadNotionCrashDbId(context)
        val debug = MBAPreferences.loadDebug(context)

        MBALog.enabled = debug

        if (crashDir == null || notionApiKey == null || ticketDbId == null) {
            MBALog.e(TAG, "Missing config: crashDir=$crashDir, apiKey=${notionApiKey?.take(8)}, ticketDb=${ticketDbId?.take(8)}")
            return Result.failure()
        }

        MBALog.d(TAG, "Config loaded: crashDir=$crashDir, ticketDb=${ticketDbId.take(8)}..., crashDb=${crashDbId?.take(8) ?: "none"}")

        // 2. Read pending crash files
        val pendingCrashes = PendingCrashProcessor.readPending(crashDir)
        if (pendingCrashes.isEmpty()) {
            MBALog.i(TAG, "No pending crashes to upload. Done.")
            return Result.success()
        }

        MBALog.i(TAG, "Processing ${pendingCrashes.size} pending crash(es)...")

        // 3. Setup dependencies
        val backend = NotionTicketBackend(
            apiKey = notionApiKey,
            bugTicketDbId = ticketDbId,
            crashReportDbId = crashDbId,
        )
        val dedupCache = LocalDedupCache(maxSize = 500, ttl = 24.hours)

        var successCount = 0
        var failCount = 0

        // 4. Process each crash file
        for ((file, rawReport) in pendingCrashes) {
            try {
                MBALog.d(TAG, "Processing: ${file.name} (${rawReport.exceptionType})")

                // Build ProcessedCrashReport (no AI)
                val report = CrashReportBuilder.build(rawReport)

                // Dedup check
                if (dedupCache.contains(report.fingerprint)) {
                    MBALog.w(TAG, "Duplicate: ${report.fingerprint.take(12)}... — deleting file")
                    file.delete()
                    successCount++
                    continue
                }

                // Push to Notion
                MBALog.d(TAG, "Uploading to Notion: '${report.title}'")
                val result = backend.createTicket(report)

                if (result.success) {
                    MBALog.i(TAG, "✅ Uploaded: ${file.name} → ticket=${result.ticketId.take(12)}...")
                    dedupCache.put(report.fingerprint)
                    file.delete()
                    successCount++
                } else {
                    MBALog.e(TAG, "❌ Upload failed for ${file.name}: ${result.errorMessage}")
                    failCount++
                }
            } catch (e: Exception) {
                MBALog.e(TAG, "❌ Error processing ${file.name}", e)
                failCount++
            }
        }

        backend.close()

        MBALog.i(TAG, "Done: $successCount uploaded, $failCount failed out of ${pendingCrashes.size}")

        return if (failCount > 0 && runAttemptCount < 3) {
            MBALog.w(TAG, "Some uploads failed — requesting retry")
            Result.retry()
        } else {
            Result.success()
        }
    }
}
