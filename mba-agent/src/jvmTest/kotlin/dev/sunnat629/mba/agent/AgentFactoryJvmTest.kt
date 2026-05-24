package dev.sunnat629.mba.agent

import ai.koog.prompt.llm.LLMCapability
import dev.sunnat629.mba.core.config.LLM
import dev.sunnat629.mba.core.config.LLMConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentFactoryJvmTest {
    @Test
    fun agentFactoryIsCommonAnalysisBoundaryImplementation() {
        assertIs<CrashAnalysisExecutorFactory>(AgentFactory(LLMConfig.NONE))
    }

    @Test
    fun geminiKoogModelUsesKnownCapabilities() {
        val model = koogModelForConfig(LLM.gemini("test-key"))

        assertEquals("gemini-2.5-flash", model.id)
        assertTrue(model.supports(LLMCapability.Completion))
    }
}
