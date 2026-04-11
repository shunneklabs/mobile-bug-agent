package dev.sunnat629.mba.core.model

import kotlinx.serialization.Serializable

/**
 * Device information captured at crash time.
 *
 * **Public API** — external devs may construct this for custom integrations
 * or read it from ticket results.
 */
@Serializable
public data class DeviceContext(
    val manufacturer: String,
    val model: String,
    val marketingName: String? = null,
    val osVersion: String,
    val sdkInt: Int,
    val locale: String,
    val totalMemoryMb: Long,
    val availableMemoryMb: Long,
    val isLowMemory: Boolean = false,
    val screenDensity: Float = 1.0f,
    val orientation: String = "portrait",
) {
    /** "Samsung Galaxy S24 (Android 15, API 35)" */
    public val displayName: String
        get() = buildString {
            append(marketingName ?: "$manufacturer $model")
            append(" (Android $osVersion, API $sdkInt)")
        }
}
