package dev.sunnat629.mba.core.pii

import dev.sunnat629.mba.core.MBALog

internal class PIISanitizer(
    customPatterns: List<Regex> = emptyList(),
    private val replacement: String = "[REDACTED]",
) {
    private val patterns: List<Regex> = buildList {
        add(Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}"""))
        add(Regex("""\+?\d[\d\-\s()]{7,}\d"""))
        add(Regex("""\b\d{4}[\s\-]?\d{4}[\s\-]?\d{4}[\s\-]?\d{1,7}\b"""))
        add(Regex("""Bearer\s+[a-zA-Z0-9._\-/+=]+"""))
        add(Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b"""))
        add(Regex("""\b(?:[A-F0-9]{1,4}:){7}[A-F0-9]{1,4}\b""", RegexOption.IGNORE_CASE))
        addAll(customPatterns)
    }

    fun scrub(input: String): String {
        var result = input
        var redactionCount = 0
        for (pattern in patterns) {
            val before = result
            result = pattern.replace(result, replacement)
            if (result != before) redactionCount++
        }
        if (redactionCount > 0) {
            MBALog.d("PII", "Scrubbed $redactionCount PII pattern(s) from ${input.length}-char input")
        }
        return result
    }

    fun containsPII(input: String): Boolean =
        patterns.any { it.containsMatchIn(input) }
}
