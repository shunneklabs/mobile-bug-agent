package dev.sunnat629.mba.server

import dev.sunnat629.mba.core.store.LocalDedupCache
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Instant

private val logger = LoggerFactory.getLogger("FileDedupPersistence")

/**
 * Persists [LocalDedupCache] state to a JSON file on disk.
 * Survives server restarts — prevents duplicate ticket creation for known crashes.
 *
 * File format:
 * ```json
 * { "entries": { "fingerprint1": "2026-04-10T22:00:00Z", ... } }
 * ```
 */
object FileDedupPersistence {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /**
     * Save the current cache state to disk.
     * Best-effort — logs errors but does not throw.
     */
    fun save(cache: LocalDedupCache, filePath: String) {
        try {
            val snapshot = cache.snapshot()
            val data = DedupFileData(
                entries = snapshot.mapValues { it.value.toString() },
            )
            val file = File(filePath)
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(data))
            logger.info("Saved ${snapshot.size} dedup entries to $filePath")
        } catch (e: Exception) {
            logger.error("Failed to save dedup cache to $filePath", e)
        }
    }

    /**
     * Restore cache state from disk.
     * Best-effort — if file doesn't exist or is corrupt, starts with empty cache.
     */
    fun restore(cache: LocalDedupCache, filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                logger.info("No dedup cache file found at $filePath — starting fresh")
                return
            }

            val data = json.decodeFromString<DedupFileData>(file.readText())
            val entries = data.entries.mapValues { (_, timestamp) ->
                Instant.parse(timestamp)
            }
            cache.restore(entries)
            logger.info("Restored ${entries.size} dedup entries from $filePath")
        } catch (e: Exception) {
            logger.error("Failed to restore dedup cache from $filePath — starting fresh", e)
        }
    }
}

@Serializable
private data class DedupFileData(
    val entries: Map<String, String> = emptyMap(),
)
