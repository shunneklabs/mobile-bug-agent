package dev.sunnat629.mba.jvm.capture

import dev.sunnat629.mba.core.MBA
import java.io.PrintWriter
import java.io.StringWriter

/**
 * JVM-specific crash handler integration.
 * Although MBA.install() sets the default handler, this class can provide
 * extra JVM-specific context or manual triggers.
 */
object JVMCrashHandler : Thread.UncaughtExceptionHandler {
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * Install the JVM crash handler.
     */
    fun install(crashDirPath: String) {
        MBA.install(crashDirPath)
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            // JVM-specific context can be added as metadata
            val metadata = mapOf(
                "jvm.version" to System.getProperty("java.version"),
                "jvm.vendor" to System.getProperty("java.vendor"),
                "os.name" to System.getProperty("os.name"),
                "os.arch" to System.getProperty("os.arch"),
                "os.version" to System.getProperty("os.version")
            )

            MBA.handleCrash(
                throwable = e,
                isFatal = true,
                threadName = t.name,
                metadata = metadata
            )
        } catch (ex: Exception) {
            System.err.println("Error capturing JVM crash: ${ex.message}")
        } finally {
            // Forward to default handler
            defaultHandler?.uncaughtException(t, e)
        }
    }
}
