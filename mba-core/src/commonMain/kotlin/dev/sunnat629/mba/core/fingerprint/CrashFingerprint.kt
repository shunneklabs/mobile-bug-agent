package dev.sunnat629.mba.core.fingerprint

import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.model.RawCrashReport
import org.kotlincrypto.hash.sha2.SHA256

internal object CrashFingerprint {

    private const val DEFAULT_TOP_FRAMES = 5
    private val LINE_NUMBER_REGEX = Regex(":\\d+\\)")

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
                if (ignoreLineNumbers) frame.replace(LINE_NUMBER_REGEX, ")")
                else frame
            }
            .joinToString("\n")

        val input = "$exceptionType\n$frames"
        val hash = sha256Hex(input)
        MBALog.d("Fingerprint", "Computed: ${hash.take(12)}... for $exceptionType (${frames.lines().size} frames)")
        return hash
    }

    fun compute(report: RawCrashReport): String =
        compute(exceptionType = report.exceptionType, stackTrace = report.stackTrace)

    private fun sha256Hex(input: String): String {
        val digest = SHA256().digest(input.encodeToByteArray())
        return digest.joinToString("") { byte ->
            (0xFF and byte.toInt()).toString(16).padStart(2, '0')
        }
    }
}
