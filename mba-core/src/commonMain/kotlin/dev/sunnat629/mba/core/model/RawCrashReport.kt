package dev.sunnat629.mba.core.model

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Immutable snapshot captured at crash time.
 *
 * **Internal** — external devs never construct this directly.
 * Written to disk synchronously by the crash handler.
 * AI processing happens later in the background.
 */
@Serializable
public data class RawCrashReport(
    val id: String,
    val timestamp: Instant = Clock.System.now(),
    val exceptionType: String,
    val message: String? = null,
    val stackTrace: String,
    val threadName: String,
    val isFatal: Boolean = true,
    val device: DeviceContext,
    val appVersion: String,
    val buildType: String,
    val currentScreen: String? = null,
    val breadcrumbs: List<String> = emptyList(),
    val customMetadata: Map<String, String> = emptyMap(),
    /**
     * When true, the server will attempt to open a GitHub auto-fix PR
     * (issue → branch → patch → draft PR) for this crash, gated by severity.
     *
     * Default: `false` (Notion-only path).
     */
    val autoFix: Boolean = false,
    /**
     * When true, the server will skip creating a Notion ticket for this crash.
     *
     * Combined with [autoFix]:
     *  - `autoFix=true,  skipNotion=false` → both Notion ticket + GitHub PR.
     *  - `autoFix=true,  skipNotion=true`  → GitHub PR only.
     *  - `autoFix=false, skipNotion=false` → Notion ticket only (today's path).
     *  - `autoFix=false, skipNotion=true`  → analysis only, no external ticket (dry-run).
     */
    val skipNotion: Boolean = false,
)
