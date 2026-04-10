package dev.sunnat629.mba.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BreadcrumbTrackerTest {

    @Test
    fun addAndSnapshot() {
        val tracker = BreadcrumbTracker(maxSize = 10)
        tracker.add("navigated to home")
        tracker.add("tapped checkout")

        val snapshot = tracker.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals("navigated to home", snapshot[0])
        assertEquals("tapped checkout", snapshot[1])
    }

    @Test
    fun maxSizeEvictsOldest() {
        val tracker = BreadcrumbTracker(maxSize = 3)
        tracker.add("a")
        tracker.add("b")
        tracker.add("c")
        tracker.add("d") // should evict "a"

        val snapshot = tracker.snapshot()
        assertEquals(3, snapshot.size)
        assertEquals(listOf("b", "c", "d"), snapshot)
    }

    @Test
    fun emptyMessagesAreRejected() {
        val tracker = BreadcrumbTracker(maxSize = 10)
        tracker.add("")
        tracker.add("   ")
        tracker.add("  \t  ")

        assertEquals(0, tracker.snapshot().size)
    }

    @Test
    fun messagesAreTrimmed() {
        val tracker = BreadcrumbTracker(maxSize = 10)
        tracker.add("  hello world  ")

        assertEquals("hello world", tracker.snapshot().first())
    }

    @Test
    fun clearRemovesAll() {
        val tracker = BreadcrumbTracker(maxSize = 10)
        tracker.add("a")
        tracker.add("b")
        tracker.clear()

        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun concurrentAddDoesNotCrash() {
        val tracker = BreadcrumbTracker(maxSize = 100)
        val threads = (1..10).map { threadId ->
            Thread {
                repeat(50) { i ->
                    tracker.add("thread-$threadId-item-$i")
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Should not crash; size should be <= 100
        assertTrue(tracker.snapshot().size <= 100)
        assertTrue(tracker.snapshot().isNotEmpty())
    }
}
