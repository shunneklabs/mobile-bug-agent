package dev.sunnat629.mba.core.model

import kotlinx.serialization.Serializable

@Serializable
data class DeviceContext(
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
    val displayName: String
        get() = buildString {
            append(marketingName ?: "$manufacturer $model")
            append(" (Android $osVersion, API $sdkInt)")
        }
}
