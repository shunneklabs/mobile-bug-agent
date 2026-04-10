package dev.sunnat629.mba.core.pii

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PIISanitizerTest {

    private val sanitizer = PIISanitizer()

    @Test
    fun scrubsEmailAddresses() {
        val input = "User john.doe@example.com crashed at line 42"
        val result = sanitizer.scrub(input)
        assertEquals("User [REDACTED] crashed at line 42", result)
    }

    @Test
    fun scrubsBearerTokens() {
        val input = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.abc123"
        val result = sanitizer.scrub(input)
        assertTrue(result.contains("[REDACTED]"))
        assertFalse(result.contains("eyJhbGci"))
    }

    @Test
    fun scrubsIPv4Addresses() {
        val input = "Connected to server at 192.168.1.100 on port 8080"
        val result = sanitizer.scrub(input)
        assertEquals("Connected to server at [REDACTED] on port 8080", result)
    }

    @Test
    fun scrubsIPv6Addresses() {
        val input = "IPv6: 2001:0db8:85a3:0000:0000:8a2e:0370:7334"
        val result = sanitizer.scrub(input)
        assertTrue(result.contains("[REDACTED]"))
        assertFalse(result.contains("2001:0db8"))
    }

    @Test
    fun preservesCleanText() {
        val input = "at com.example.app.MainActivity.onCreate(MainActivity.kt:42)"
        val result = sanitizer.scrub(input)
        assertEquals(input, result)
    }

    @Test
    fun scrubsMultiplePIITypes() {
        val input = "User admin@corp.com from 10.0.0.1 with Bearer abc123token"
        val result = sanitizer.scrub(input)
        assertFalse(result.contains("admin@corp.com"))
        assertFalse(result.contains("10.0.0.1"))
        assertFalse(result.contains("abc123token"))
    }

    @Test
    fun customPatternsWork() {
        val customSanitizer = PIISanitizer(
            customPatterns = listOf(Regex("""SECRET_\w+""")),
        )
        val input = "Key is SECRET_ABC123DEF"
        val result = customSanitizer.scrub(input)
        assertEquals("Key is [REDACTED]", result)
    }

    @Test
    fun containsPIIDetectsEmails() {
        assertTrue(sanitizer.containsPII("contact user@test.com"))
        assertFalse(sanitizer.containsPII("plain stack trace text"))
    }
}
