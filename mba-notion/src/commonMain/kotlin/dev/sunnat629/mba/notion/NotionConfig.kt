package dev.sunnat629.mba.notion

/**
 * Runtime configuration for Notion API access.
 *
 * NOTE: Keep this as data-only. Token storage is the host app/server responsibility.
 */
data class NotionConfig(
    val token: String,
    /** Database ID where crash tickets will be created. (Not the URL; the UUID-like id) */
    val databaseId: String,
    /** Notion-Version header value (e.g. "2022-06-28"). */
    val notionVersion: String = "2022-06-28",
    val baseUrl: String = "https://api.notion.com/v1",
)
