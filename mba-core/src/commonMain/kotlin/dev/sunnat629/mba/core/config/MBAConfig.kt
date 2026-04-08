package dev.sunnat629.mba.core.config

import dev.sunnat629.mba.core.pii.PIISanitizer
import dev.sunnat629.mba.core.model.Severity
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Immutable configuration for the MBA SDK.
 * Built via [Builder] DSL. Validated on build().
 */
data class MBAConfig(
    val mode: MBAMode,
    val llm: LLMConfig,
    val piiSanitizer: PIISanitizer,
    val agentConfig: AgentConfig,
    val debug: Boolean,
) {
    data class AgentConfig(
        val piiScrubbing: Boolean = true,
        val severityThreshold: Severity = Severity.LOW,
        val localDedupWindow: Duration = 24.hours,
        val maxDedupCacheSize: Int = 100,
        val maxCrashesPerBatch: Int = 10,
    )

    class Builder {
        var mode: MBAMode? = null
        var llm: LLMConfig? = null
        var debug: Boolean = false

        private var piiPatterns: MutableList<Regex> = mutableListOf()
        private var agentConfig = AgentConfig()

        /** Add custom PII regex patterns. */
        fun piiPatterns(vararg patterns: Regex) {
            piiPatterns.addAll(patterns)
        }

        /** Configure the on-device agent. */
        fun onDeviceAgent(block: AgentConfigBuilder.() -> Unit) {
            agentConfig = AgentConfigBuilder().apply(block).build()
        }

        fun build(): MBAConfig {
            val resolvedMode = requireNotNull(mode) {
                "MBA mode must be set. Use MBAMode.SdkOnly(...) or MBAMode.Saas(...)"
            }

            // Resolve LLM config: explicit llm > mode's llmApiKey > error
            val resolvedLlm = llm ?: when (resolvedMode) {
                is MBAMode.SdkOnly -> LLM.gemini(resolvedMode.llmApiKey)
                is MBAMode.Saas -> LLMConfig.NONE  // SaaS uses server-side LLM
                is MBAMode.SelfHosted -> LLMConfig.NONE
            }

            return MBAConfig(
                mode = resolvedMode,
                llm = resolvedLlm,
                piiSanitizer = PIISanitizer(customPatterns = piiPatterns),
                agentConfig = agentConfig,
                debug = debug,
            )
        }
    }

    class AgentConfigBuilder {
        var piiScrubbing: Boolean = true
        var severityThreshold: Severity = Severity.LOW
        var localDedupWindow: Duration = 24.hours
        var maxDedupCacheSize: Int = 100
        var maxCrashesPerBatch: Int = 10

        internal fun build() = AgentConfig(
            piiScrubbing = piiScrubbing,
            severityThreshold = severityThreshold,
            localDedupWindow = localDedupWindow,
            maxDedupCacheSize = maxDedupCacheSize,
            maxCrashesPerBatch = maxCrashesPerBatch,
        )
    }
}
