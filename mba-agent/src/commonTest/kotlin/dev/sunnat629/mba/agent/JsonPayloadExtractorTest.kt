package dev.sunnat629.mba.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonPayloadExtractorTest {
    @Test
    fun `extracts json from markdown fenced response`() {
        val response = """
            ```json
            {
              "rootException": "java.lang.NullPointerException",
              "rootMessage": null
            }
            ```
        """.trimIndent()

        assertEquals(
            """
                {
                  "rootException": "java.lang.NullPointerException",
                  "rootMessage": null
                }
            """.trimIndent(),
            extractJsonObjectPayload(response),
        )
    }

    @Test
    fun `keeps nested braces inside strings`() {
        val response = "prefix {\"message\":\"keep {inner} text\",\"ok\":true} suffix"

        assertEquals(
            "{\"message\":\"keep {inner} text\",\"ok\":true}",
            extractJsonObjectPayload(response),
        )
    }
}