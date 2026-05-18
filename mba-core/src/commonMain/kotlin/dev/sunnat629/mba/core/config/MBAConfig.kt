package dev.sunnat629.mba.core.config

import dev.sunnat629.mba.core.pii.PIISanitizer
import dev.sunnat629.mba.core.model.Severity
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Immutable configuration for the MBA SDK.
 * Built via [Builder] DSL. Validated on [Builder.build].
 *
 * External devs interact ONLY with [Builder] — the config itself is opaque.
 */
public class MBAConfig internal constructor(
    internal val mode: MBAMode,
    internal val llm: LLMConfig,
    internal val piiSanitizer: PIISanitizer,
    internal val agentConfig: AgentConfig,
    public val debug: Boolean,
    /**
     * When false, SDKOnly processing skips local LLM/Koog analysis and uses
     * raw-derived fallback reports for callbacks and optional app-owned sinks.
     *
     * Default: `true`.
     */
    public val useAgent: Boolean = true,
    /**
     * When true, every captured crash is flagged for the server's GitHub
     * auto-fix path (issue → branch → patch → draft PR). The server still
     * gates the actual PR on crash severity (HIGH/CRITICAL only).
     *
     * Default: `false`.
     */
    public val autoFix: Boolean = false,
    /**
     * When true, the server will skip creating a Notion ticket for crashes
     * captured by this SDK instance. Combine with [autoFix] for a GitHub-only
     * pipeline, or use alone for a "dry-run" pipeline (analysis only).
     *
     * Default: `false`.
     */
    public val skipNotion: Boolean = false,
) {

    internal data class AgentConfig(
        val piiScrubbing: Boolean = true,
        val severityThreshold: Severity = Severity.LOW,
        val localDedupWindow: Duration = 24.hours,
        val maxDedupCacheSize: Int = 100,
        val maxCrashesPerBatch: Int = 10,
    )

    /**
     * DSL builder — the ONLY way external devs create config.
     *
     * ```kotlin
     * MBA.init(crashDir) {
     *     mode = MBAMode.SdkOnly(llm = LLM.ollama(model = "llama3.2:latest"))
     *     debug = true
     * }
     * ```
     */
    public class Builder {
        /** Required. Deployment mode. */
        public var mode: MBAMode? = null

        /** Optional. Override LLM config (defaults derived from mode). */
        public var llm: LLMConfig? = null

        /** Enable debug logging. Default false. */
        public var debug: Boolean = false

        /**
         * Enable local Koog/LLM analysis in SDKOnly mode. Set false when the
         * app wants raw fallback callbacks/tickets without an LLM key.
         */
        public var useAgent: Boolean = true

        /**
         * Opt in to the server-side GitHub auto-fix path. Default `false`.
         * Server still gates the actual PR on crash severity (HIGH/CRITICAL).
         */
        public var autoFix: Boolean = false

        /**
         * Skip Notion ticket creation server-side. Default `false`.
         * Combine with [autoFix] for a GitHub-only pipeline.
         */
        public var skipNotion: Boolean = false

        private var piiPatterns: MutableList<Regex> = mutableListOf()
        private var agentConfig = AgentConfig()

        /** Add custom PII regex patterns to scrub from crash data. */
        public fun piiPatterns(vararg patterns: Regex) {
            piiPatterns.addAll(patterns)
        }

        /** Fine-tune the on-device agent behavior. */
        public fun agent(block: AgentConfigBuilder.() -> Unit) {
            agentConfig = AgentConfigBuilder().apply(block).build()
        }

        public fun build(): MBAConfig {
            val resolvedMode = requireNotNull(mode) {
                "MBA mode must be set. Use MBAMode.SdkOnly(...) or MBAMode.Saas(...)"
            }

            // Resolve LLM config: explicit builder llm > mode llm > legacy Gemini key.
            val resolvedLlm = llm ?: when (resolvedMode) {
                is MBAMode.SdkOnly -> {
                    val modeLlm = resolvedMode.llm
                    require(modeLlm != null || resolvedMode.llmApiKey.isNotBlank() || !useAgent) {
                        "SdkOnly mode with useAgent=true requires an LLMConfig or legacy llmApiKey."
                    }
                    if (useAgent) {
                        (modeLlm ?: LLM.gemini(resolvedMode.llmApiKey)).also { config ->
                            require(config.model.isNotBlank()) {
                                "SdkOnly mode with useAgent=true requires a non-blank LLM model."
                            }
                            require(!config.requiresApiKey || config.apiKey.isNotBlank()) {
                                "SdkOnly mode with ${config.provider} requires a non-blank LLM API key."
                            }
                            require(config.provider != LLM.Provider.CUSTOM || !config.endpoint.isNullOrBlank()) {
                                "SdkOnly custom LLM provider requires a non-blank endpoint."
                            }
                        }
                    } else {
                        LLMConfig.NONE
                    }
                }
                is MBAMode.Saas -> LLMConfig.NONE
                is MBAMode.SelfHosted -> LLMConfig.NONE
            }

            return MBAConfig(
                mode = resolvedMode,
                llm = resolvedLlm,
                piiSanitizer = PIISanitizer(customPatterns = piiPatterns),
                agentConfig = agentConfig,
                debug = debug,
                useAgent = useAgent,
                autoFix = autoFix,
                skipNotion = skipNotion,
            )
        }
    }

    /** Fine-grained agent settings. Most devs won't need this. */
    public class AgentConfigBuilder {
        public var piiScrubbing: Boolean = true
        public var severityThreshold: Severity = Severity.LOW
        public var localDedupWindow: Duration = 24.hours
        public var maxDedupCacheSize: Int = 100
        public var maxCrashesPerBatch: Int = 10

        internal fun build() = AgentConfig(
            piiScrubbing = piiScrubbing,
            severityThreshold = severityThreshold,
            localDedupWindow = localDedupWindow,
            maxDedupCacheSize = maxDedupCacheSize,
            maxCrashesPerBatch = maxCrashesPerBatch,
        )
    }
}
