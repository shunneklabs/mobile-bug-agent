package dev.sunnat629.mba.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubRuntimeConfigTest {
    @Test
    fun `loads documented target owner and repo aliases`() {
        val config = GitHubRuntimeConfigLoader.load(
            mapOf(
                "GITHUB_TOKEN" to "token-1",
                "GITHUB_TARGET_OWNER" to "shunneklabs",
                "GITHUB_TARGET_REPO" to "mobile-bug-agent",
            )::get,
        )

        assertTrue(config.isConfigured)
        assertEquals("token-1", config.token)
        assertEquals("shunneklabs", config.owner)
        assertEquals("mobile-bug-agent", config.repo)
        assertEquals("main", config.baseBranch)
    }

    @Test
    fun `parses owner repo from GitHub repository value`() {
        val config = GitHubRuntimeConfigLoader.load(
            mapOf(
                "GITHUB_TOKEN" to "token-1",
                "GITHUB_REPO" to "https://github.com/shunneklabs/mobile-bug-agent",
                "GITHUB_BASE_BRANCH" to "master",
            )::get,
        )

        assertTrue(config.isConfigured)
        assertEquals("shunneklabs", config.owner)
        assertEquals("mobile-bug-agent", config.repo)
        assertEquals("master", config.baseBranch)
    }

    @Test
    fun `configuration message lists missing GitHub settings`() {
        val config = GitHubRuntimeConfigLoader.load(emptyMap<String, String>()::get)

        assertFalse(config.isConfigured)
        assertTrue(config.configurationMessage.contains("GITHUB_TOKEN"))
        assertTrue(config.configurationMessage.contains("GITHUB_OWNER or GITHUB_TARGET_OWNER"))
        assertTrue(config.configurationMessage.contains("GITHUB_REPO or GITHUB_TARGET_REPO"))
    }
}