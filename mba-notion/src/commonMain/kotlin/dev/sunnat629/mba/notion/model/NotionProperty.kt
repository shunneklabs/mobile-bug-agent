package dev.sunnat629.mba.notion.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Minimal property builders for Notion page create/update.
 */
object NotionProperty {

    fun title(text: String): JsonElement = buildJsonObject {
        put(
            "title",
            buildJsonArray {
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
            buildJsonArray {
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

    /** Relation to one or more pages (page ids). */
    fun relation(vararg pageIds: String): JsonElement = buildJsonObject {
        put(
            "relation",
            buildJsonArray {
                pageIds.forEach { id ->
                    add(buildJsonObject { put("id", id) })
                }
            }
        )
    }

    fun readNumber(property: JsonElement): Double? {
        val obj = property as? JsonObject ?: return null
        val prim = obj["number"] as? JsonPrimitive ?: return null
        return prim.content.toDoubleOrNull()
    }

    /**
     * Best-effort extraction of the rendered plain-text from a title/rich_text property.
     */
    fun readPlainText(property: JsonElement): String? {
        val obj = property as? JsonObject ?: return null
        val type = (obj["type"] as? JsonPrimitive)?.content

        val arr = when (type) {
            "rich_text" -> obj["rich_text"]
            "title" -> obj["title"]
            else -> obj["rich_text"] ?: obj["title"]
        }

        val jsonArr = arr as? JsonArray ?: return null
        val parts = jsonArr.mapNotNull { item ->
            val itemObj = item as? JsonObject ?: return@mapNotNull null
            val plain = itemObj["plain_text"] as? JsonPrimitive
            plain?.content
        }
        return parts.joinToString("")
    }
}

@Serializable
internal data class NotionErrorResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("message") val message: String? = null,
)
