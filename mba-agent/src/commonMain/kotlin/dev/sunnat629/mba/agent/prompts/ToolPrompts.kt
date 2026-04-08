package dev.sunnat629.mba.agent.prompts

object ToolPrompts {

    val STACK_TRACE_PARSER: String = """
        Analyze the following Android stack trace and extract structured information.

        Input: A raw JVM/Android stack trace string.

        Extract:
        - rootException
        - rootMessage
        - crashFile (IN APP CODE)
        - crashLine
        - crashMethod
        - isAppCode
        - callChain (max 5 frames)
        - frameworkContext

        Respond ONLY with JSON matching ParsedStackTrace.
        If you cannot determine a field, set it to null / false / empty list. Do not guess.
    """.trimIndent()

    val SEVERITY_CLASSIFIER: String = """
        Classify the severity of an Android crash based on the parsed stack trace and device context.

        Respond ONLY with JSON matching SeverityResult.
        confidence must be between 0.0 and 1.0.
    """.trimIndent()

    val SUMMARY_GENERATOR: String = """
        Generate a human-readable bug report from a parsed Android crash.

        Respond ONLY with JSON matching CrashSummary.
        Do NOT include raw stack traces in the description.
    """.trimIndent()

    val DUPLICATE_CHECKER: String = """
        Determine if this crash is a duplicate of an existing crash report.

        Respond ONLY with JSON matching DuplicateCheckResult.
    """.trimIndent()
}
