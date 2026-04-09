package dev.sunnat629.mba.core.store

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * On-device LRU cache of crash fingerprints.
 * Prevents re-sending known crashes to the LLM (saves cost + latency).
 *
 * - Max [maxSize] entries (default 100)
 * - TTL of [ttl] (default 24h) — expired entries are lazily evicted
 * - Supports [snapshot]/[restore] for disk persistence across app restarts
 *
 * **Internal** — external devs configure via [MBAConfig.Builder.agent].
 *
 * Thread-safety: @Synchronized on all public methods (JVM/Android only).
 */
internal class LocalDedupCache(
    private val maxSize: Int = 100,
    private val ttl: Duration = 24.hours,
) {
    // fingerprint → last seen timestamp. Access-ordered for LRU eviction.
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

    /** Update last-seen time for an existing entry (duplicate detected). */
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
    fun clear(): Unit = cache.clear()

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
        // Use iterator for safe removal during iteration
        val iter = cache.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if ((now - entry.value) > ttl) {
                iter.remove()
            }
        }
    }
}
