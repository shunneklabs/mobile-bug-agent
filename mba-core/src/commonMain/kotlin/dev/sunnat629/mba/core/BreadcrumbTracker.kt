package dev.sunnat629.mba.core

/**
 * Simple in-memory breadcrumb tracker used for crash context.
 *
 * Thread-safe via @Synchronized (JVM/Android).
 * If iOS target is added, replace with kotlinx.atomicfu.locks.SynchronizedObject.
 *
 * Kept intentionally minimal — no allocations on the hot path.
 */
internal class BreadcrumbTracker(
    private val maxSize: Int = 50,
) {
    private val buffer: ArrayDeque<String> = ArrayDeque(maxSize)

    @Synchronized
    fun add(message: String) {
        val m = message.trim()
        if (m.isEmpty()) return
        if (buffer.size == maxSize) {
            buffer.removeFirst()
        }
        buffer.addLast(m)
    }

    @Synchronized
    fun snapshot(): List<String> = buffer.toList()

    @Synchronized
    fun clear() {
        buffer.clear()
    }
}
