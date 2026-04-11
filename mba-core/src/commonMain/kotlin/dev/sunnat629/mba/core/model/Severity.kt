package dev.sunnat629.mba.core.model

import kotlinx.serialization.Serializable

/**
 * Crash severity level.
 *
 * **Public API** — used in config (severityThreshold) and ticket output.
 */
@Serializable
public enum class Severity(public val weight: Int, public val emoji: String, public val label: String) {
    CRITICAL(4, "\uD83D\uDD34", "Critical"),
    HIGH(3, "\uD83D\uDFE0", "High"),
    MEDIUM(2, "\uD83D\uDFE1", "Medium"),
    LOW(1, "\uD83D\uDFE2", "Low");

    public companion object {
        /** Parse from LLM output string, case-insensitive. Defaults to MEDIUM. */
        public fun fromString(value: String): Severity =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: MEDIUM
    }
}
