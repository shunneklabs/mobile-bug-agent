package dev.sunnat629.mba.android

import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.model.RawCrashReport
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Reads crash JSON files written by CrashWriter during previous sessions.
 *
 * Returns a list of (File, RawCrashReport) pairs.
 * The File reference is needed so the caller can delete it after successful upload.
 *
 * Malformed files are logged and skipped (not deleted — may be debugged manually).
 */
internal object PendingCrashProcessor {

    private const val TAG = "PendingCrash"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Read all pending crash files from the crash directory.
     *
     * @return List of (file, parsed report) pairs. Empty if no files or dir doesn't exist.
     */
    fun readPending(crashDir: String): List<Pair<File, RawCrashReport>> {
        val dir = File(crashDir)
        if (!dir.exists() || !dir.isDirectory) {
            MBALog.d(TAG, "Crash dir doesn't exist or not a directory: $crashDir")
            return emptyList()
        }

        val crashFiles = dir.listFiles { file ->
            file.isFile && file.name.startsWith("mba_crash_") && file.name.endsWith(".json")
        } ?: emptyList()

        if (crashFiles.isEmpty()) {
            MBALog.d(TAG, "No pending crash files found")
            return emptyList()
        }

        MBALog.i(TAG, "Found ${crashFiles.size} pending crash file(s)")

        val results = mutableListOf<Pair<File, RawCrashReport>>()

        for (file in crashFiles) {
            try {
                val content = file.readText()
                val report = json.decodeFromString<RawCrashReport>(content)
                results.add(file to report)
                MBALog.d(TAG, "Parsed: ${file.name} → ${report.exceptionType} (${report.id})")
            } catch (e: Exception) {
                MBALog.e(TAG, "Failed to parse ${file.name} — skipping (file kept for manual debug)", e)
            }
        }

        return results
    }
}
