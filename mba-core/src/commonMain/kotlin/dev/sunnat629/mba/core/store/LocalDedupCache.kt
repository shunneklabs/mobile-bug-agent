package dev.sunnat629.mba.core.store

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * On-device LRU cache of crash fingerprints.
 * Prevents re-sending known crashes to the LLM.
 *
 * - Max [maxSize] entries (default 100)
 * - TTL of [ttl] (default 24 hours)
 * - Persisted to disk via [save]/[load] for survival across app restarts
 *
 * Thread-safety: synchronized on all public methods.
 */
class LocalDedupCache(
    private val maxSize: Int = 100,
    private val ttl: Duration = 24.hours,
) {
    // fingerprint -> last seen timestamp
    private val cache = LinkedHashMap<String, Instant>(maxSize, 0.75f, true)

    @Synchronized
    fun contains(fingerprint: String): Boolean {
        evictExpired()
        return cache.containsKey(fingerprint)
    }

    @Synchronized
    fun put(fingerprint: String) {
        evictExpired()
        cache[fingerprint] = Clock.System.now()
        // Evict oldest if over capacity
        while (cache.size > maxSize) {
            val oldest = cache.entries.first()
            cache.remove(oldest.key)
        }
    }

    /** Update last-seen time for an existing entry (used when duplicate is detected). */
    @Synchronized
    fun touch(fingerprint: String) {
        if (cache.containsKey(fingerprint)) {
            cache[fingerprint] = Clock.System.now()
        }
    }

    @Synchronized
    fun size(): Int {
        evictExpired()
        return cache.size
    }

    @Synchronized
    fun clear() = cache.clear()

    /** Export cache state for disk persistence. */
    @Synchronized
    fun snapshot(): Map<String, Instant> = cache.toMap()

    /** Restore cache from disk. */
    @Synchronized
    fun restore(data: Map<String, Instant>) {
        cache.clear()
        cache.putAll(data)
        evictExpired()
    }

    private fun evictExpired() {
        val now = Clock.System.now()
        val toRemove = mutableListOf<String>()
        cache.forEach { (fingerprint, lastSeen) ->
            if ((now - lastSeen) > ttl) {
                toRemove.add(fingerprint)
            }
        }
        toRemove.forEach { cache.remove(it) }
    }
}
