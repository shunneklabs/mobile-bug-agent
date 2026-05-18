package dev.sunnat629.mba.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class MBAConfigTest {

    @Test
    fun sdkOnlyWithoutAgentAllowsBlankLlmKey() {
        val config = MBAConfig.Builder().apply {
            mode = MBAMode.SdkOnly(llmApiKey = "")
            useAgent = false
        }.build()

        assertFalse(config.useAgent)
        assertEquals(LLMConfig.NONE, config.llm)
    }

    @Test
    fun sdkOnlyWithAgentRequiresLlmKey() {
        assertFailsWith<IllegalArgumentException> {
            MBAConfig.Builder().apply {
                mode = MBAMode.SdkOnly(llmApiKey = "")
                useAgent = true
            }.build()
        }
    }
}
