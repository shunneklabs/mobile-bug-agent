package dev.sunnat629.mba.notion.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class NotionCreatePageRequest(
    val parent: Parent,
    val properties: Map<String, JsonElement>,
) {
    @Serializable
    data class Parent(
        @SerialName("database_id") val databaseId: String,
    )
}

@Serializable
data class NotionCreatePageResponse(
    val id: String,
    @SerialName("url") val url: String? = null,
)
