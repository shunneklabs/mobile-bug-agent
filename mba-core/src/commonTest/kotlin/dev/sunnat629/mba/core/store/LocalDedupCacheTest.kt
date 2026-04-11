package dev.sunnat629.mba.core.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class LocalDedupCacheTest {

    @Test
    fun putAndContains() {
        val cache = LocalDedupCache(maxSize = 10, ttl = 24.hours)
        assertFalse(cache.contains("fp1"))

        cache.put("fp1")
        assertTrue(cache.contains("fp1"))
        assertFalse(cache.contains("fp2"))
    }

    @Test
    fun evictsOldestWhenOverCapacity() {
        val cache = LocalDedupCache(maxSize = 3, ttl = 24.hours)
        cache.put("fp1")
        cache.put("fp2")
        cache.put("fp3")
        assertEquals(3, cache.size())

        // Adding 4th should evict the oldest (fp1)
        cache.put("fp4")
        assertEquals(3, cache.size())
        assertFalse(cache.contains("fp1"), "fp1 should have been evicted")
        assertTrue(cache.contains("fp2"))
        assertTrue(cache.contains("fp3"))
        assertTrue(cache.contains("fp4"))
    }

    @Test
    fun clearRemovesAll() {
        val cache = LocalDedupCache(maxSize = 10, ttl = 24.hours)
        cache.put("fp1")
        cache.put("fp2")
        assertEquals(2, cache.size())

        cache.clear()
        assertEquals(0, cache.size())
        assertFalse(cache.contains("fp1"))
    }

    @Test
    fun snapshotAndRestoreRoundTrip() {
        val cache = LocalDedupCache(maxSize = 10, ttl = 24.hours)
        cache.put("fp1")
        cache.put("fp2")

        val snapshot = cache.snapshot()
        assertEquals(2, snapshot.size)

        // Restore into a new cache
        val cache2 = LocalDedupCache(maxSize = 10, ttl = 24.hours)
        cache2.restore(snapshot)
        assertTrue(cache2.contains("fp1"))
        assertTrue(cache2.contains("fp2"))
    }

    @Test
    fun touchUpdatesEntry() {
        val cache = LocalDedupCache(maxSize = 10, ttl = 24.hours)
        cache.put("fp1")
        assertTrue(cache.contains("fp1"))

        // touch should not throw or remove
        cache.touch("fp1")
        assertTrue(cache.contains("fp1"))

        // touch on non-existing key should be no-op
        cache.touch("nonexistent")
        assertFalse(cache.contains("nonexistent"))
    }

    @Test
    fun duplicatePutUpdatesTimestamp() {
        val cache = LocalDedupCache(maxSize = 10, ttl = 24.hours)
        cache.put("fp1")
        cache.put("fp1") // should update, not add duplicate
        assertEquals(1, cache.size())
        assertTrue(cache.contains("fp1"))
    }
}
