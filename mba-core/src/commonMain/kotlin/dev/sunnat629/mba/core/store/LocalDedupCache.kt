package dev.sunnat629.mba.core.store

import dev.sunnat629.mba.core.MBALog
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

internal class LocalDedupCache(
    private val maxSize: Int = 100,
    private val ttl: Duration = 24.hours,
) {
    private val cache = LinkedHashMap<String, Instant>(maxSize, 0.75f, true)

    @Synchronized
    fun contains(fingerprint: String): Boolean {
        evictExpired()
        val hit = cache.containsKey(fingerprint)
        MBALog.d("DedupCache", "contains(${fingerprint.take(12)}...) = $hit (size=${cache.size})")
        return hit
    }

    @Synchronized
    fun put(fingerprint: String) {
        evictExpired()
        cache[fingerprint] = Clock.System.now()
        while (cache.size > maxSize) {
            val oldest = cache.entries.first()
            MBALog.d("DedupCache", "LRU eviction: ${oldest.key.take(12)}...")
            cache.remove(oldest.key)
        }
        MBALog.d("DedupCache", "put(${fingerprint.take(12)}...) → size=${cache.size}")
    }

    @Synchronized
    fun touch(fingerprint: String) {
        if (cache.containsKey(fingerprint)) {
            cache[fingerprint] = Clock.System.now()
            MBALog.d("DedupCache", "touch(${fingerprint.take(12)}...) — updated timestamp")
        }
    }

    @Synchronized
    fun size(): Int {
        evictExpired()
        return cache.size
    }

    @Synchronized
    fun clear(): Unit = cache.clear()

    @Synchronized
    fun snapshot(): Map<String, Instant> = cache.toMap()

    @Synchronized
    fun restore(data: Map<String, Instant>) {
        cache.clear()
        cache.putAll(data)
        evictExpired()
        MBALog.i("DedupCache", "Restored ${cache.size} entries from disk")
    }

    private fun evictExpired() {
        val now = Clock.System.now()
        var evicted = 0
        val iter = cache.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if ((now - entry.value) > ttl) {
                iter.remove()
                evicted++
            }
        }
        if (evicted > 0) {
            MBALog.d("DedupCache", "TTL eviction: removed $evicted expired entries")
        }
    }
}
