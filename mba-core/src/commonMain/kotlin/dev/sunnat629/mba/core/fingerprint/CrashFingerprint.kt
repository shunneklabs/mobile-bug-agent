package dev.sunnat629.mba.core.fingerprint

import dev.sunnat629.mba.core.MBALog
import dev.sunnat629.mba.core.model.RawCrashReport
import org.kotlincrypto.hash.sha2.SHA256

public object CrashFingerprint {

    private const val DEFAULT_TOP_FRAMES = 5
    private val LINE_NUMBER_REGEX = Regex(":\\d+\\)")
    private val UUID_REGEX = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val ISO_TIMESTAMP_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}[T ][0-9:.+-]+Z?")
    private val EPOCH_MILLIS_REGEX = Regex("\\b1[0-9]{12}\\b")
    private val VOLATILE_ID_REGEX = Regex("(?i)\\b(session|device|user|trace|request|install)[-_ ]?id=([^\\s,;)]+)")

    public fun compute(
        exceptionType: String,
        stackTrace: String,
        topFrames: Int = DEFAULT_TOP_FRAMES,
        ignoreLineNumbers: Boolean = false,
    ): String {
        val normalizedStackTrace = normalize(stackTrace)
        val frames = normalizedStackTrace
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

    public fun compute(report: RawCrashReport): String =
        compute(exceptionType = report.exceptionType, stackTrace = report.stackTrace)

    private fun normalize(value: String): String =
        value
            .replace(UUID_REGEX, "<uuid>")
            .replace(ISO_TIMESTAMP_REGEX, "<timestamp>")
            .replace(EPOCH_MILLIS_REGEX, "<timestamp>")
            .replace(VOLATILE_ID_REGEX) { match -> "${match.groupValues[1]}Id=<id>" }

    private fun sha256Hex(input: String): String {
        val digest = SHA256().digest(input.encodeToByteArray())
        return digest.joinToString("") { byte ->
            (0xFF and byte.toInt()).toString(16).padStart(2, '0')
        }
    }
}
