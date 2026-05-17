package dev.sunnat629.mba.agent.prompts

internal object ToolPrompts {

    val STACK_TRACE_PARSER: String = """
        Analyze this Android stack trace. Return JSON:
        {
          "rootException": "java.lang.NullPointerException",
          "rootMessage": "...",
          "crashFile": "CheckoutViewModel.kt",
          "crashLine": 87,
          "crashMethod": "processPayment",
          "isAppCode": true,
          "callChain": [{"file":"...","line":87,"method":"...","isApp":true}],
          "frameworkContext": "Coroutine after ViewModel cleared"
        }
        Focus on app code frames (not android.*, androidx.*, java.*, kotlin.*).
        Set null for fields you cannot determine. Do not guess.
    """.trimIndent()

    val SEVERITY_CLASSIFIER: String = """
        Classify crash severity. Return JSON:
        {
          "severity": "HIGH",
          "confidence": 0.85,
          "reasoning": "..."
        }
        Levels: CRITICAL (data loss/security/payment), HIGH (main flow crash),
        MEDIUM (edge case), LOW (cosmetic/rare).
    """.trimIndent()

    val SUMMARY_GENERATOR: String = """
        Generate a bug report. Return JSON:
        {
          "title": "Checkout crashes on rotation during payment",
          "description": "NullPointerException in CheckoutViewModel.kt:87...",
          "stepsToReproduce": "1. Open checkout\n2. Start payment\n3. Rotate",
          "possibleCause": "Payment coroutine outlives ViewModel..."
        }
        Required:
        - stepsToReproduce MUST be non-null. Use breadcrumbs/current screen when present.
        - possibleCause MUST be non-null. Tie it to exception type, app frame, method, or lifecycle context.
        - description MUST mention the failing method/file when known and include the affected app version/build context if provided.
        Title format: "[Screen] [what happens] [when/trigger]".
        Be direct. No "I think". No raw stack traces in description.
    """.trimIndent()

    val DUPLICATE_CHECKER: String = """
        Check if this crash is a duplicate. Return JSON:
        {
          "isDuplicate": false,
          "matchType": null,
          "matchedCrashId": null,
          "confidence": 0.95,
          "reasoning": "..."
        }
        matchType: "exact" (same fingerprint) or "semantic" (same root cause, different line).
    """.trimIndent()
}
