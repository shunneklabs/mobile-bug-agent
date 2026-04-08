package dev.sunnat629.mba.core.fingerprint

import org.kotlincrypto.hash.sha2.SHA256
import dev.sunnat629.mba.core.model.RawCrashReport

/**
 * Deterministic crash fingerprinting.
 * Same crash on different devices = same fingerprint.
 * Used for dedup both locally and in the crash store.
 */
object CrashFingerprint {

    private const val DEFAULT_TOP_FRAMES = 5

    /**
     * Compute SHA-256 hash from exception type + top N stack frames.
     * Strips line numbers from consideration if [ignoreLineNumbers] is true
     * (useful when code changes shift line numbers but the crash is the same).
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
                    // "at com.app.Foo.bar(Foo.kt:42)" → "at com.app.Foo.bar(Foo.kt)"
                    frame.replace(Regex(":\\d+\\)"), ")")
                } else {
                    frame
                }
            }
            .joinToString("\n")

        val input = "$exceptionType\n$frames"
        return sha256Hex(input)
    }

    /**
     * Compute fingerprint directly from a RawCrashReport.
     */
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
