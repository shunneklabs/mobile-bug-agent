package dev.sunnat629.mba.server.middleware

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import dev.sunnat629.mba.core.MBALog
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Simple sliding-window rate limiter.
 * Tracks request timestamps per key (IP or API key).
 * Rejects when count exceeds [maxRequests] within [window].
 */
class RateLimiter(
    private val maxRequests: Int = 10,
    private val window: Duration = 1.seconds,
) {
    private companion object {
        private const val TAG = "RateLimiter"
    }

    private val buckets = ConcurrentHashMap<String, MutableList<Long>>()

    fun isAllowed(key: String): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()
        val windowStart = now - window.inWholeMilliseconds

        val bucket = buckets.computeIfAbsent(key) { mutableListOf() }

        synchronized(bucket) {
            bucket.removeAll { it < windowStart }
            if (bucket.size >= maxRequests) {
                MBALog.w(TAG, "Rate limit exceeded for key=$key (${bucket.size}/$maxRequests in window)")
                return false
            }
            bucket.add(now)
            return true
        }
    }
}

/**
 * Ktor plugin that applies rate limiting to all requests.
 * Uses X-MBA-API-Key as the rate limit key.
 */
fun Application.rateLimiter(limiter: RateLimiter) {
    intercept(ApplicationCallPipeline.Call) {
        val key = call.request.header("X-MBA-API-Key") ?: call.request.local.remoteHost
        if (!limiter.isAllowed(key)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Rate limit exceeded"))
            finish()
        }
    }
}
