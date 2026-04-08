package dev.sunnat629.mba.notion.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Minimal property builders for Notion page create/update.
 *
 * This is intentionally partial; extend as needed.
 */
object NotionProperty {

    fun title(text: String): JsonElement = buildJsonObject {
        put(
            "title",
            kotlinx.serialization.json.buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put(
                            "text",
                            buildJsonObject {
                                put("content", text)
                            }
                        )
                    }
                )
            }
        )
    }

    fun richText(text: String): JsonElement = buildJsonObject {
        put(
            "rich_text",
            kotlinx.serialization.json.buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put(
                            "text",
                            buildJsonObject {
                                put("content", text)
                            }
                        )
                    }
                )
            }
        )
    }

    fun select(name: String): JsonElement = buildJsonObject {
        put("select", buildJsonObject { put("name", name) })
    }

    fun number(value: Number): JsonElement = buildJsonObject {
        put("number", value.toDouble())
    }

    fun checkbox(value: Boolean): JsonElement = buildJsonObject {
        put("checkbox", value)
    }
}

@Serializable
internal data class NotionErrorResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("message") val message: String? = null,
)
