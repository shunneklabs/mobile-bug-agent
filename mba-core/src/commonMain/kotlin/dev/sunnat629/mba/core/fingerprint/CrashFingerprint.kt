package dev.sunnat629.mba.core.fingerprint

import org.kotlincrypto.hash.sha2.SHA256
import dev.sunnat629.mba.core.model.RawCrashReport

/**
 * Deterministic crash fingerprinting.
 *
 * Same crash on different devices = same fingerprint.
 * Used for dedup both locally (on-device cache) and in the crash store.
 *
 * **Internal** — external devs never call this directly.
 */
internal object CrashFingerprint {

    private const val DEFAULT_TOP_FRAMES = 5

    // Pre-compiled regexes — avoid recompilation on every call.
    private val LINE_NUMBER_REGEX = Regex(":\\d+\\)")

    /**
     * Compute SHA-256 hash from exception type + top N stack frames.
     *
     * @param ignoreLineNumbers Strip line numbers so code-shift doesn't split groups.
     */
    fun compute(
        exceptionType: String,
        stackTrace: String,
        topFrames: Int = DEFAULT_TOP_FRAMES,
        ignoreLineNumbers: Boolean = false,
    ): String {
        val frames = stackTrace
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("at ") }
            .take(topFrames)
            .map { frame ->
                if (ignoreLineNumbers) {
                    frame.replace(LINE_NUMBER_REGEX, ")")
                } else {
                    frame
                }
            }
            .joinToString("\n")

        val input = "$exceptionType\n$frames"
        return sha256Hex(input)
    }

    /** Compute fingerprint directly from a [RawCrashReport]. */
    fun compute(report: RawCrashReport): String =
        compute(
            exceptionType = report.exceptionType,
            stackTrace = report.stackTrace,
        )

    private fun sha256Hex(input: String): String {
        val digest = SHA256().digest(input.encodeToByteArray())
        return digest.joinToString("") { byte ->
            (0xFF and byte.toInt()).toString(16).padStart(2, '0')
        }
    }
}
