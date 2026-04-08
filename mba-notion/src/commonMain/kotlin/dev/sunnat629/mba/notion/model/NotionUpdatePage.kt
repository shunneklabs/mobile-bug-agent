package dev.sunnat629.mba.notion.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class NotionUpdatePageRequest(
    val properties: Map<String, JsonElement>,
)

@Serializable
data class NotionUpdatePageResponse(
    val id: String,
    @SerialName("url") val url: String? = null,
)
