package dev.sunnat629.mba.notion.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Minimal shape for retrieving a Notion page. We only need the id/url and properties.
 */
@Serializable
data class NotionRetrievePageResponse(
    val id: String,
    @SerialName("url") val url: String? = null,
    val properties: Map<String, JsonElement> = emptyMap(),
)
