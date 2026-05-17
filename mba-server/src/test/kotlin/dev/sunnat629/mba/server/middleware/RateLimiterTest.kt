package dev.sunnat629.mba.server.middleware

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {
    @Test
    fun `allows localhost requests beyond limit`() {
        val limiter = RateLimiter(maxRequests = 1)

        assertTrue(isLocalOrDevRequest("localhost", null, null))
        assertTrue(limiter.isAllowed("localhost"))
        assertTrue(limiter.isAllowed("localhost"))
    }

    @Test
    fun `allows loopback origin requests beyond limit`() {
        val limiter = RateLimiter(maxRequests = 1)

        assertTrue(isLocalOrDevRequest("192.168.1.10", "http://127.0.0.1:8080", null))
        assertTrue(limiter.isAllowed("http://127.0.0.1:8080"))
        assertTrue(limiter.isAllowed("http://127.0.0.1:8080"))
    }

    @Test
    fun `keeps limiting non local requests`() {
        val limiter = RateLimiter(maxRequests = 1)

        assertFalse(isLocalOrDevRequest("203.0.113.10", "https://example.com", null, environment = null))
        assertTrue(limiter.isAllowed("203.0.113.10"))
        assertFalse(limiter.isAllowed("203.0.113.10"))
    }

    @Test
    fun `rate limiting is scoped to report endpoint`() {
        assertTrue(shouldRateLimitPath("/report"))
        assertFalse(shouldRateLimitPath("/stats"))
        assertFalse(shouldRateLimitPath("/events"))
        assertFalse(shouldRateLimitPath("/booth"))
    }
}