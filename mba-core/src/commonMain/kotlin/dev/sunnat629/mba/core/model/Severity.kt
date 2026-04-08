package dev.sunnat629.mba.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Severity(val weight: Int, val emoji: String, val label: String) {
    CRITICAL(4, "🔴", "Critical"),
    HIGH(3, "🟠", "High"),
    MEDIUM(2, "🟡", "Medium"),
    LOW(1, "🟢", "Low");

    companion object {
        /** Parse from LLM output string, case-insensitive. Defaults to MEDIUM. */
        fun fromString(value: String): Severity =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: MEDIUM
    }
}
