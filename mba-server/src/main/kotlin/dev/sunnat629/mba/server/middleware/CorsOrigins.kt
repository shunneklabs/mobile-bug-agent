package dev.sunnat629.mba.server.middleware

import java.net.URI

internal data class CorsOrigin(
    val host: String,
    val schemes: List<String>,
)

internal fun parseAllowedCorsOrigins(value: String?): List<CorsOrigin> {
    val configured = value
        ?.split(',')
        ?.mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toCorsOriginOrNull() }
        .orEmpty()

    return configured.ifEmpty { defaultAllowedCorsOrigins() }
}

private fun defaultAllowedCorsOrigins(): List<CorsOrigin> = listOf(
    CorsOrigin("localhost:3000", listOf("http")),
    CorsOrigin("localhost:8080", listOf("http")),
    CorsOrigin("localhost:63342", listOf("http")),
    CorsOrigin("127.0.0.1:8080", listOf("http")),
)

private fun String.toCorsOriginOrNull(): CorsOrigin? {
    if (!contains("://")) {
        return CorsOrigin(this, listOf("https", "http"))
    }

    val uri = runCatching { URI(this) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host ?: return null
    val hostWithPort = if (uri.port > 0) "$host:${uri.port}" else host
    return CorsOrigin(hostWithPort, listOf(scheme))
}