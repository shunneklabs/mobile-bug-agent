package dev.sunnat629.mba.agent.tools

import dev.sunnat629.mba.agent.model.ParsedStackTrace
import dev.sunnat629.mba.agent.model.StackFrame

/**
 * MVP implementation: deterministic parsing without LLM.
 *
 * The Koog/Gemini pipeline can replace/augment this later.
 */
object StackTraceParserTool {

    private val appPrefixes = listOf("dev.sunnat629.")
    private val nonAppPrefixes = listOf(
        "android.",
        "androidx.",
        "java.",
        "javax.",
        "kotlin.",
        "kotlinx.",
        "com.google.",
        "org.jetbrains.",
    )

    fun parse(stackTrace: String): ParsedStackTrace {
        val lines = stackTrace.lines()

        val (rootException, rootMessage) = parseHeader(lines.firstOrNull().orEmpty())

        val frames = lines
            .asSequence()
            .mapNotNull(::parseFrame)
            .toList()

        val crashFrame = frames.firstOrNull { it.isApp } ?: frames.firstOrNull()

        return ParsedStackTrace(
            rootException = rootException.ifEmpty { "Throwable" },
            rootMessage = rootMessage,
            crashFile = crashFrame?.file,
            crashLine = crashFrame?.line,
            crashMethod = crashFrame?.method,
            isAppCode = crashFrame?.isApp ?: false,
            callChain = frames.take(5),
            frameworkContext = null,
        )
    }

    private fun parseHeader(header: String): Pair<String, String?> {
        // Example: java.lang.NullPointerException: message
        val idx = header.indexOf(':')
        return if (idx >= 0) {
            header.substring(0, idx).trim() to header.substring(idx + 1).trim().ifEmpty { null }
        } else {
            header.trim() to null
        }
    }

    private fun parseFrame(line: String): StackFrame? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("at ")) return null

        // at dev.foo.Bar.baz(Bar.kt:12)
        val body = trimmed.removePrefix("at ")
        val open = body.indexOf('(')
        val close = body.lastIndexOf(')')
        if (open <= 0 || close <= open) return null

        val methodFq = body.substring(0, open)
        val location = body.substring(open + 1, close)

        val file: String
        val lineNo: Int?
        val colon = location.lastIndexOf(':')
        if (colon > 0 && colon < location.length - 1) {
            file = location.substring(0, colon)
            lineNo = location.substring(colon + 1).toIntOrNull()
        } else {
            file = location
            lineNo = null
        }

        val isApp = isAppMethod(methodFq)

        return StackFrame(
            file = file,
            line = lineNo,
            method = methodFq.substringAfterLast('.'),
            isApp = isApp,
        )
    }

    private fun isAppMethod(methodFq: String): Boolean {
        if (appPrefixes.any { methodFq.startsWith(it) }) return true
        if (nonAppPrefixes.any { methodFq.startsWith(it) }) return false
        // Unknown package: treat as non-app to avoid false positives.
        return false
    }
}
