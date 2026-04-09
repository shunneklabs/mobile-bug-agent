package dev.sunnat629.mba.core.pii

/**
 * Regex-based PII scrubber. Runs BEFORE any data leaves the device.
 *
 * Fast (~1-3ms), deterministic, no network needed.
 * Patterns are compiled once at construction time.
 *
 * Default patterns: email, phone, credit card, bearer token, IPv4, IPv6.
 * Developers can add custom patterns via [MBAConfig.Builder.piiPatterns].
 *
 * **Internal** — external devs configure PII scrubbing via the builder,
 * never interact with this class directly.
 */
internal class PIISanitizer(
    customPatterns: List<Regex> = emptyList(),
    private val replacement: String = "[REDACTED]",
) {
    private val patterns: List<Regex> = buildList {
        // Email
        add(Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}"""))
        // Phone (international formats)
        add(Regex("""\+?\d[\d\-\s()]{7,}\d"""))
        // Credit card (13-19 digits with optional separators)
        add(Regex("""\b\d{4}[\s\-]?\d{4}[\s\-]?\d{4}[\s\-]?\d{1,7}\b"""))
        // Bearer token
        add(Regex("""Bearer\s+[a-zA-Z0-9._\-/+=]+"""))
        // IPv4
        add(Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b"""))
        // IPv6 (full form)
        add(Regex("""\b(?:[A-F0-9]{1,4}:){7}[A-F0-9]{1,4}\b""", RegexOption.IGNORE_CASE))
        addAll(customPatterns)
    }

    /** Scrub PII from the input string. Returns sanitized copy. */
    fun scrub(input: String): String {
        var result = input
        for (pattern in patterns) {
            result = pattern.replace(result, replacement)
        }
        return result
    }

    /** Check if the input contains any PII patterns. Useful for testing. */
    fun containsPII(input: String): Boolean =
        patterns.any { it.containsMatchIn(input) }
}
