package dev.sunnat629.mba.agent.tools

import dev.sunnat629.mba.agent.model.DuplicateCheckResult

/**
 * MVP duplicate checker.
 *
 * - Exact match should be done by fingerprint elsewhere.
 * - Semantic match requires remote store querying + embeddings; not in MVP.
 */
object DuplicateCheckerTool {

    fun check(
        fingerprint: String,
        existingFingerprints: Set<String>,
    ): DuplicateCheckResult {
        val isDup = existingFingerprints.contains(fingerprint)
        return if (isDup) {
            DuplicateCheckResult(
                isDuplicate = true,
                matchType = "exact",
                matchedCrashId = null,
                confidence = 1.0f,
                reasoning = "Exact fingerprint match."
            )
        } else {
            DuplicateCheckResult(
                isDuplicate = false,
                matchType = null,
                matchedCrashId = null,
                confidence = 0.9f,
                reasoning = "No exact fingerprint match in provided set."
            )
        }
    }
}
