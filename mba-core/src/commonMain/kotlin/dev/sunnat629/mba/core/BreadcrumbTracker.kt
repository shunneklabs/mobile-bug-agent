package dev.sunnat629.mba.core

/**
 * Thread-safe circular buffer for user action breadcrumbs.
 * Zero allocation on add (reuses array slots).
 */
class BreadcrumbTracker(
    private val maxSize: Int = 20,
) {
    private val buffer = arrayOfNulls<String>(maxSize)
    private var head = 0
    private var count = 0

    @Synchronized
    fun add(message: String) {
        buffer[head] = message
        head = (head + 1) % maxSize
        if (count < maxSize) count++
    }

    /** Returns breadcrumbs in chronological order. Thread-safe snapshot. */
    @Synchronized
    fun snapshot(): List<String> {
        if (count == 0) return emptyList()
        val result = ArrayList<String>(count)
        val start = if (count < maxSize) 0 else head
        for (i in 0 until count) {
            val idx = (start + i) % maxSize
            buffer[idx]?.let { result.add(it) }
        }
        return result
    }

    @Synchronized
    fun clear() {
        buffer.fill(null)
        head = 0
        count = 0
    }
}
