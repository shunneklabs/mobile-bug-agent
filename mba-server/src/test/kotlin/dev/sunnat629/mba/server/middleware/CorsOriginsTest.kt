package dev.sunnat629.mba.server.middleware

import kotlin.test.Test
import kotlin.test.assertEquals

class CorsOriginsTest {
    @Test
    fun `uses localhost defaults when no origins are configured`() {
        val origins = parseAllowedCorsOrigins(null)

        assertEquals(CorsOrigin("localhost:8080", listOf("http")), origins[1])
    }

    @Test
    fun `parses configured origin urls into explicit hosts and schemes`() {
        val origins = parseAllowedCorsOrigins("https://demo.example.com, http://localhost:8080")

        assertEquals(
            listOf(
                CorsOrigin("demo.example.com", listOf("https")),
                CorsOrigin("localhost:8080", listOf("http")),
            ),
            origins,
        )
    }
}