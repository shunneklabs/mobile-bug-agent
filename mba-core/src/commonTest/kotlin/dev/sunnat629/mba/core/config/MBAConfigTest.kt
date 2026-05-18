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

    @Test
    fun sdkOnlyUsesExplicitModeLlmConfig() {
        val config = MBAConfig.Builder().apply {
            mode = MBAMode.SdkOnly(
                llm = LLM.ollama(
                    model = "qwen2.5-coder:7b",
                    endpoint = "http://10.0.2.2:11434",
                ),
            )
            useAgent = true
        }.build()

        assertEquals(LLM.Provider.OLLAMA, config.llm.provider)
        assertEquals("qwen2.5-coder:7b", config.llm.model)
        assertEquals("http://10.0.2.2:11434", config.llm.endpoint)
    }

    @Test
    fun sdkOnlyCustomProviderRequiresEndpoint() {
        assertFailsWith<IllegalArgumentException> {
            MBAConfig.Builder().apply {
                mode = MBAMode.SdkOnly(
                    llm = LLMConfig(
                        provider = LLM.Provider.CUSTOM,
                        apiKey = "",
                        model = "local-model",
                    ),
                )
                useAgent = true
            }.build()
        }
    }
}
