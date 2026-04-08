package dev.sunnat629.mba.notion.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class NotionPageRequest(
    val parent: NotionParent,
    val properties: Map<String, NotionProperty>,
    val children: List<NotionBlock>? = null
)

@Serializable
data class NotionParent(
    @SerialName("database_id") val databaseId: String
)

@Serializable
sealed class NotionProperty {
    @Serializable
    @SerialName("title")
    data class Title(val title: List<NotionRichText>) : NotionProperty()

    @Serializable
    @SerialName("rich_text")
    data class RichText(val rich_text: List<NotionRichText>) : NotionProperty()

    @Serializable
    @SerialName("select")
    data class Select(val select: NotionSelectItem) : NotionProperty()

    @Serializable
    @SerialName("number")
    data class Number(val number: Double) : NotionProperty()

    @Serializable
    @SerialName("date")
    data class Date(val date: NotionDate) : NotionProperty()
}

@Serializable
data class NotionRichText(
    val type: String = "text",
    val text: NotionTextContent
)

@Serializable
data class NotionTextContent(
    val content: String
)

@Serializable
data class NotionSelectItem(
    val name: String
)

@Serializable
data class NotionDate(
    val start: String
)

@Serializable
data class NotionBlock(
    val `object`: String = "block",
    val type: String,
    val code: NotionCodeBlock? = null,
    val paragraph: NotionParagraphBlock? = null
)

@Serializable
data class NotionCodeBlock(
    val rich_text: List<NotionRichText>,
    val language: String = "kotlin"
)

@Serializable
data class NotionParagraphBlock(
    val rich_text: List<NotionRichText>
)

@Serializable
data class NotionPageResponse(
    val id: String,
    val url: String? = null
)
