package dev.sunnat629.mba.notion.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class NotionPageRequest(
    val parent: NotionParent,
    val properties: Map<String, NotionProperty>,
    val children: List<NotionBlock>? = null
)

@Serializable
public data class NotionParent(
    @SerialName("database_id") val databaseId: String
)

@Serializable
public sealed class NotionProperty {
    @Serializable
    @SerialName("title")
    public data class Title(val title: List<NotionRichText>) : NotionProperty()

    @Serializable
    @SerialName("rich_text")
    public data class RichText(val rich_text: List<NotionRichText>) : NotionProperty()

    @Serializable
    @SerialName("select")
    public data class Select(val select: NotionSelectItem) : NotionProperty()

    @Serializable
    @SerialName("number")
    public data class Number(val number: Double) : NotionProperty()

    @Serializable
    @SerialName("date")
    public data class Date(val date: NotionDate) : NotionProperty()

    @Serializable
    @SerialName("relation")
    public data class Relation(val relation: List<NotionRelationItem>) : NotionProperty()
}

@Serializable
public data class NotionRichText(
    val type: String = "text",
    val text: NotionTextContent
)

@Serializable
public data class NotionTextContent(
    val content: String
)

@Serializable
public data class NotionSelectItem(
    val name: String
)

@Serializable
public data class NotionDate(
    val start: String
)

@Serializable
public data class NotionRelationItem(
    val id: String
)

@Serializable
public data class NotionBlock(
    val `object`: String = "block",
    val type: String,
    val code: NotionCodeBlock? = null,
    val paragraph: NotionParagraphBlock? = null
)

@Serializable
public data class NotionCodeBlock(
    val rich_text: List<NotionRichText>,
    val language: String = "kotlin"
)

@Serializable
public data class NotionParagraphBlock(
    val rich_text: List<NotionRichText>
)

@Serializable
public data class NotionPageResponse(
    val id: String,
    val url: String? = null
)

@Serializable
public data class NotionDatabaseResponse(
    val id: String,
    val properties: Map<String, NotionDatabaseProperty> = emptyMap(),
)

@Serializable
public data class NotionDatabaseProperty(
    val id: String? = null,
    val type: String,
)
