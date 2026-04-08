package dev.sunnat629.mba.notion.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NotionQueryDatabaseRequest(
    val page_size: Int = 10,
    val filter: Filter? = null,
) {
    @Serializable
    sealed interface Filter {
        val property: String

        @Serializable
        data class RichTextEquals(
            override val property: String,
            @SerialName("rich_text") val richText: RichText,
        ) : Filter {
            @Serializable
            data class RichText(
                @SerialName("equals") val equals: String,
            )
        }
    }
}

@Serializable
data class NotionQueryDatabaseResponse(
    val results: List<ResultPage> = emptyList(),
) {
    @Serializable
    data class ResultPage(
        val id: String,
        @SerialName("url") val url: String? = null,
    )
}
