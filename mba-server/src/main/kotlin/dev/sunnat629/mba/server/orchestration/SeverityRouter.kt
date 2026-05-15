package dev.sunnat629.mba.server.orchestration

import dev.sunnat629.mba.core.model.Severity

enum class RoutingDecision {
    NotifyOnly,
    AutoFix,
}

/**
 * Deterministic severity gate for deciding whether a crash can go through auto-fix.
 *
 * `MBA_AUTOFIX_ENABLED=false` (default) hard-stops auto-fix and forces notify-only.
 */
class SeverityRouter(
    private val autoFixEnabled: Boolean = isAutoFixEnabledFromEnv(),
) {
    fun route(severity: Severity): RoutingDecision {
        if (!autoFixEnabled) return RoutingDecision.NotifyOnly

        return when (severity) {
            Severity.CRITICAL,
            Severity.HIGH,
            Severity.MEDIUM,
            -> RoutingDecision.NotifyOnly

            Severity.LOW -> RoutingDecision.AutoFix
        }
    }

    companion object {
        internal fun isAutoFixEnabledFromEnv(): Boolean =
            System.getenv("MBA_AUTOFIX_ENABLED")
                ?.trim()
                ?.lowercase()
                ?.let { it == "true" || it == "1" || it == "yes" }
                ?: false
    }
}
