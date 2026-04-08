package dev.sunnat629.mba.notion

/**
 * Runtime configuration for Notion API access.
 *
 * The Notion HTTP API expects database IDs in the request path.
 * To make setup easier, this accepts either:
 * - a raw Notion database UUID (with or without dashes)
 * - a Notion database URL (we will extract the ID)
 */
data class NotionConfig(
    val token: String,
    /** Database id or URL. */
    val databaseId: String,
    /** Notion-Version header value (e.g. "2022-06-28"). */
    val notionVersion: String = "2022-06-28",
    val baseUrl: String = "https://api.notion.com/v1",
) {
    fun normalizedDatabaseId(): String = normalizeNotionId(databaseId)

    companion object {
        /** Extract and normalize a Notion id from either an id or a URL. */
        fun normalizeNotionId(idOrUrl: String): String {
            val s = idOrUrl.trim()

            // If user pasted a full URL, the id is usually the last path segment.
            val candidate = s.substringAfterLast('/')
                .substringBefore('?')
                .substringBefore('#')

            // Remove non-hex chars (dashes) and keep last 32 chars if longer.
            val hex = candidate.filter { it.isLetterOrDigit() }.lowercase()
            return when {
                hex.length >= 32 -> hex.takeLast(32)
                else -> hex
            }
        }
    }
}
