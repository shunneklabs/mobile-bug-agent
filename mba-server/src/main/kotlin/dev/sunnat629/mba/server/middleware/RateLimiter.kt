package dev.sunnat629.mba.server.middleware

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import dev.sunnat629.mba.core.MBALog
import java.net.URI
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
        if (key.isLocalDevHost() || key.requestHost()?.isLocalDevHost() == true) return true

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
        val remoteHost = call.request.local.remoteHost
        if (isLocalOrDevRequest(remoteHost, call.request.header(HttpHeaders.Origin), call.request.header(HttpHeaders.Referrer))) {
            return@intercept
        }

        val key = call.request.header("X-MBA-API-Key") ?: remoteHost
        if (!limiter.isAllowed(key)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Rate limit exceeded"))
            finish()
        }
    }
}

internal fun isLocalOrDevRequest(
    remoteHost: String,
    origin: String?,
    referrer: String?,
    environment: String? = devEnvironmentFromEnv(),
): Boolean {
    if (remoteHost.isLocalDevHost()) return true
    if (origin?.requestHost()?.isLocalDevHost() == true) return true
    if (referrer?.requestHost()?.isLocalDevHost() == true) return true

    return environment?.isDevEnvironment() == true
}

private fun devEnvironmentFromEnv(): String? =
    System.getenv("MBA_ENV") ?: System.getenv("APP_ENV") ?: System.getenv("KTOR_ENV")

private fun String.isLocalDevHost(): Boolean {
    val host = trim().trim('[', ']').lowercase()
    return host == "localhost" ||
        host == "127.0.0.1" ||
        host == "0.0.0.0" ||
        host == "::1" ||
        host == "0:0:0:0:0:0:0:1"
}

private fun String.requestHost(): String? =
    runCatching { URI(this).host }.getOrNull()

private fun String.isDevEnvironment(): Boolean =
    trim().lowercase().let { it == "dev" || it == "development" || it == "local" }
