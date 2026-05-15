package dev.sunnat629.mba.core

public class BreadcrumbTracker(
    private val maxSize: Int = 50,
) {
    private val buffer: ArrayDeque<String> = ArrayDeque(maxSize)

    @Synchronized
    fun add(message: String) {
        val m = message.trim()
        if (m.isEmpty()) return
        if (buffer.size == maxSize) {
            val evicted = buffer.removeFirst()
            MBALog.d("Breadcrumb", "Buffer full ($maxSize), evicted oldest: '$evicted'")
        }
        buffer.addLast(m)
    }

    @Synchronized
    fun snapshot(): List<String> = buffer.toList()

    @Synchronized
    fun clear() {
        MBALog.d("Breadcrumb", "Cleared ${buffer.size} breadcrumbs")
        buffer.clear()
    }
}
