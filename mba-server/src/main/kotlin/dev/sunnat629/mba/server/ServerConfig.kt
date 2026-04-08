package dev.sunnat629.mba.server

import dev.sunnat629.mba.notion.NotionConfig

/**
 * Runtime configuration for mba-server.
 *
 * MVP uses environment variables.
 */
data class ServerConfig(
    val host: String = getenv("MBA_SERVER_HOST") ?: "0.0.0.0",
    val port: Int = (getenv("MBA_SERVER_PORT") ?: "8080").toInt(),
    val notion: NotionConfig = NotionConfig(
        token = getenvRequired("NOTION_TOKEN"),
        databaseId = getenvRequired("NOTION_DATABASE_ID"),
        notionVersion = getenv("NOTION_VERSION") ?: "2022-06-28",
    ),
) {
    companion object {
        fun load(): ServerConfig = ServerConfig()

        private fun getenv(name: String): String? = kotlin.system.getenv(name)

        private fun getenvRequired(name: String): String =
            getenv(name) ?: error("Missing required env var: $name")
    }
}
