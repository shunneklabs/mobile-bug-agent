package dev.sunnat629.mba.agent.model

import kotlinx.serialization.Serializable

@Serializable
internal data class ParsedStackTrace(
    val rootException: String,
    val rootMessage: String? = null,
    val crashFile: String? = null,
    val crashLine: Int? = null,
    val crashMethod: String? = null,
    val isAppCode: Boolean = false,
    val callChain: List<StackFrame> = emptyList(),
    val frameworkContext: String? = null,
)

@Serializable
internal data class StackFrame(
    val file: String,
    val line: Int? = null,
    val method: String,
    val isApp: Boolean = false,
)
