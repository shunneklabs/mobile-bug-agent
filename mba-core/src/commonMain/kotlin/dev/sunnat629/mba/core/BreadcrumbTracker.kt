package dev.sunnat629.mba.core

/**
 * Simple in-memory breadcrumb tracker used for crash context.
 *
 * Notes:
 * - Must be safe to call from any thread.
 * - Avoid heavy allocations.
 * - This is intentionally platform-agnostic and kept in mba-core so common code can use it.
 */
internal class BreadcrumbTracker(
    private val maxSize: Int = 50,
) {
    private val lock = Any()
    private val buffer: ArrayDeque<String> = ArrayDeque(maxSize)

    fun add(message: String) {
        val m = message.trim()
        if (m.isEmpty()) return
        synchronized(lock) {
            if (buffer.size == maxSize) {
                buffer.removeFirst()
            }
            buffer.addLast(m)
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { buffer.toList() }

    fun clear() {
        synchronized(lock) { buffer.clear() }
    }
}
